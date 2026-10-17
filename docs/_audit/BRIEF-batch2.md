# 派单契约 · 第二批：桌面端（对端）协议逆向

> 所有第二批子代理共用。派单提示里只写"你负责哪一块"，通用纪律以本文件为准。**不要修改本文件。**

---

## 0. 一句话目标

`E:\Zcode_android\` 的 Rust 协议核心是从**参考 Dart 实现**移植的；网页快照给了我们**移动客户端**的
视角。但**对端（被远控的 ZCode 桌面应用）自己的实现**一直没读过——它决定了：
- 我们发出去的帧**会不会被接受**（校验严格度、必填项）；
- 数值约束的**权威值**（分片上限、超时、心跳）；
- 我们**没实现**的那部分对端行为（错误码、降级、限流）。

你的任务：**只读**逆向桌面端 `app.asar` 里与远控协议相关的代码，产出规格文档。

**产物只落本项目**：`E:\Zcode_android\docs\desktop\`（**不要**写进 `E:\Zcode_harmony\Docs\`）。

---

## 1. 权威基线

| 项 | 值 |
|---|---|
| 包 | `C:\Users\wdn32\AppData\Local\Programs\ZCode\resources\app.asar` · 340,516,766 B |
| 应用版本 | `appVersion 3.15.3`（`out/metadata/build-meta.json`） |
| 构建 | `buildCommitId d7ca8bee` · `buildTime 2026-10-13T08:36:30.450Z` |
| 文件总数 | 27,856 |

**重要**：网页快照 `Reference_web/3.15.3/` 与桌面端是**同一个 3.15.3 构建**（网页资源
Last-Modified 同为 2026-10-13）。桌面端是**对端权威**；网页端是**客户端视角**。两者冲突时，
以桌面端为准并**显式记录冲突**。

---

## 2. 工具（本仓已有）

```bash
cd E:/Zcode_android

# 列目录
node tools/asar.mjs ls --depth 2
node tools/asar.mjs ls --filter "out/main/"          # 带大小
# 看一个文件
node tools/asar.mjs cat out/metadata/build-meta.json
node tools/asar.mjs dump out/main/index.js /tmp/x.js  # 落盘
# 全包检索（只扫 .js/.mjs/.cjs/.ts/.json，跳过 >60MB 与疑似二进制）
node tools/asar.mjs grep "<regex>" --path-filter "out/main/" --max-hits 8 --context 200
node tools/asar.mjs grep "<regex>" --ext .js --max-hits 20 --context 80
```

**按需抽取**（审计期间的本地副本已按「不留临时产物」清理；需要时用下面的命令重新生成，
省得每次扫 340MB）：

```bash
node tools/asar.mjs dump out/main/index.js docs/_audit/desktop-extract/main-index.js
```

下表是审计期间抽过的那 7 个文件，重新抽取时照这个清单来：

| 本地文件 | 原路径 | 大小 | 我们已知的用途 |
|---|---|---|---|
| `main-index.js` | `out/main/index.js` | 786,385 | **远控桥服务端 + 入站分发器**（`routePayload`；`bridgeSessionId` 23 次、`channel` 40 次、`pair` 27 次、`heartbeat` 21 次） |
| `chunk-OW6EUS2G.js` | `out/main/chunk-OW6EUS2G.js` | 799,588 | **共享协议模块**：`zcode_type` 判别联合（22 次）、`fragment` 41 次、`crc32`、`bridgeSessionId` 18 次 |
| `chunk-OUCOFHX4.js` | `out/host/chunk-OUCOFHX4.js` | 823,914 | **host 侧同一份共享协议模块**（用于交叉校验 OW6EUS2G） |
| `chunk-ZCWQ2IP2.js` | `out/main/chunk-ZCWQ2IP2.js` | 260,239 | **对话服务端**（`conversation` 87 次） |
| `chunk-ULQVYT3T.js` | `out/main/chunk-ULQVYT3T.js` | 98,588 | relay 客户端（`heartbeat` 10 次） |
| `chunk-ZI5VUQWA.js` | `out/main/chunk-ZI5VUQWA.js` | 52,186 | rpc-frame 传输（`fragment` 16 次、`chunkIndex`、`crc32`） |
| `host-index.js` | `out/host/index.js` | 1,681,899 | **host 进程**（`conversation` 206 次、`channel` 71 次、`pair` 14 次） |
| `build-meta.json` | `out/metadata/build-meta.json` | 141 | 版本指纹 |

**注意**：`out/preload/*.cjs`、`out/renderer/assets/*` 也含同一份 schema 与部分客户端逻辑，
按需用 `asar.mjs` 取，不必预先抽。

**已实测的定位**（请自己复核，不要照抄）：
- 帧判别联合定义在 `chunk-OW6EUS2G.js` 第 64 行附近（字节偏移 ~72.5 万）；
  `chunk-OUCOFHX4.js` 第 64 行附近（~69 万）是同构副本。
- 入站分发器在 `main-index.js` 第 408 行附近（~40.5 万）：`case"mobile-view-state-update"` /
  `"workspace-bridge-open"` / `"workspace-reconnect-request"` / `"rpc-frame"` / `"mobile-diagnostic"`
  / `"telemetry-report"` / `"platform-request"`。

---

## 3. 读法纪律（硬性）

- 压缩产物：**绝不整读大文件**。先 `grep -n`/`grep -b` 定位，再取窗口。
- **每个结论必须给可复现坐标**：文件（原 asar 内路径）+ 行号或字节偏移 + **原文片段**
  （压缩代码可以短，但必须原文，不许转述成"大概是"）。
- 压缩符号名（`h`/`C`/`e.object`/`W`）**必须连上下文给**，同名符号在不同 chunk 含义不同。
- **分不清就写「未能证实」**。推测必须显式标 `⚠️ 推测`。
- **只读、不执行**：不要 `require`/`import`/运行包里的代码，不要启动桌面应用，不要改 `app.asar`。
- 不要 `git add`/`commit`；不要动 `rust/`、`android/` 源码（读可以，改不行）。

---

## 4. 输出纪律（硬性）

1. 写到 `E:\Zcode_android\docs\desktop\<你的编号>-<标题>.md`，**一个文件**（除非派单里另有说明）。
2. 文首必须有「**审计基线**」块：
   ```
   > **审计基线**：ZCode 桌面端 3.15.3（app.asar 340,516,766 B，build `d7ca8bee`，2026-10-13）
   > 主要坐标：`out/main/index.js`、`out/main/chunk-OW6EUS2G.js`
   ```
3. **中文书写**，表格化，数字带单位与来源。
4. 每篇末尾加一节「**与我们的实现的差异**」：对照 `E:\Zcode_android\rust\crates\protocol\src\`
   与 `conversation_transport.rs`（**可读不可改**），列出**不一致**与**未实现**项。
   这一节是父对话修代码的直接输入，必须具体到文件:行。
5. 每篇末尾再加一节「**仍未证实 / 需要抓包**」，逐条写清还缺什么证据。
6. 写完回一个 **≤15 行**摘要给父对话（父对话看不到你的过程）：最关键的 3–5 条结论（带坐标）+
   与我们实现的不一致 + 未能证实的项。**完整报告写进文件，不要把长正文回给父对话。**
7. 禁止把密钥、口令、token、配对码、设备凭据写入任何文件。

---

## 5. 术语

- 「对端 / 桌面端」= `app.asar` 里的 ZCode 桌面应用（被远控方）。
- 「客户端 / 移动端」= `zcode.z.ai/remote/v4` 的移动远控页面（或我们的原生实现）。
- 「main / host / preload / renderer」= Electron 的四个进程/上下文，见 B01。
- `zcode_type` = relay 业务帧的类型判别字段。

---

## 6. 已挖到的权威事实（第一批成果，**直接用，不必重挖**）

第一批（8 个子代理）已按 3.15.3 重写了网页端文档，成果镜像在
`E:\Zcode_android\docs\remote-v4\`（13 篇，**建议先读 49 篇与 16 篇**）。以下是与你直接相关的硬事实：

### 6.1 帧的 schema 与预算

- **`zcode_type` 判别联合**：19 个字面量 / 21 个 union 臂，定义在
  `Reference_web/3.15.3/assets/validation-CqXZOLXO.js` 偏移 **284,618–287,155**（`uS=k([…])`）。
  桌面端同构副本在 `out/main/chunk-OW6EUS2G.js` 第 64 行附近（~72.5 万）。
- **`rpc-frame` 线格式预算 `bd`**（`validation` 偏移 **128,536**）：
  `maxPhysicalFrameBytes` 1 MiB、`maxMessageBytes` **16 MiB**、`maxFragments` **64**、
  `assemblyTimeoutMs` **30 s**、`transportIdMaxChars` 256。
  别与 `Uu.logicalFrameAssemblyMaxFragments: 1024`（偏移 **117,018**，桥内逻辑帧，另一个概念）混淆。
- **协议常量对象 `Uu`**（`validation` 偏移 **117,018**）：含附件、对话、命令各域上限
  （`attachmentMaxBytes` 20 MiB、`attachmentChunkMaxBytes` 512 KiB、`snapshotTailWindowRows` 60、
  `rowsRangeMaxLimit` 200、`conversationQueryTimeoutMs` 10 s、`commandPendingTtlMs` 24 h 等）。
- **附件实际上传分片 = 384 KiB**（不是 512 KiB，512 是上限）。
- **校验失败的帧在链路上静默丢弃**，接收侧证据：桌面端 `out/main/index.js`
  偏移 **384,954** 的 `handleDataPayload`：`let r=vf(t); if(!r){ logger.warn("[web-remote-control] invalid external relay payload dropped", …); return }`。
  **任何**帧校验失败都走这条路，没有错误回执。

### 6.2 已知的一个真实缺陷（请从对端视角确认并扩展）

客户端 `mobile-view-state-update` 帧里的 `deviceInfo` 若存在，其 **`updatedAt` 是必填**
（`validation` 偏移 **283,833** 的 `oS`，12 键中 8 个 `.optional()`，`updatedAt` 没有）。
参考 Dart 实现与我们的 Rust 移植都只发 `{platform,version,name}` ⇒ 该帧被丢弃 ⇒ 只丢
`h.mobileViewState`，配对/开桥/任务列表不受影响。**请确认这条并补全对端在该帧之后的行为**
（桌面端拿 `mobileViewState` 做什么、丢了会怎样）。

### 6.3 第一批推翻的旧结论（不要沿用旧稿）

- `deviceInfo` **字段**可选，但其内部键要过校验（两件事，别混）。
- `zod` 别名这一代语义与上一代**相反**（本代 `O`=object、`D`=array、`S`=string、`T`=boolean）。
- `tasks[i]` **16 键**（含 `hasBackgroundWork`、`workflowActivity`）；`workspaces[i]` **8 键**；
  task 内叫 `workspaceLabel`/`workspaceKind`，workspaces 内叫 `label`/`kind`。
- 工作区键规则仍是 `workspaceIdentity?.trim() || workspacePath`（identity 优先）。
- relay WS 形态、`transportState` 在 3.15.3 **不存在**、`liveStatus` 是**会话索引字段**而非连接态。
- 心跳 10 s ± 2 s、ack 看门狗 30 s、关闭码**无语义**（语义在 relay 顶层 `error.code`）。

### 6.4 ack 身份三元组：**已回验，必须带全三元组**（此条曾被我错误否定，现更正）

**结论**：出站 ack 必须带 `bridgeSessionId` + `bridgeGeneration` + `recoveryId` 三个字段。

**证据（已逐字复核）**：桌面端 `out/main/chunk-JS2T33SG.js` 偏移 **7,688** 附近：
```js
function ke(n,e){ if(typeof e!="object"||e===null) return !1; let r=e;
  return r.bridgeSessionId===n.bridgeSessionId
      && r.bridgeGeneration===n.bridgeGeneration
      && r.recoveryId===n.recoveryId }
a(ke,"rawIdentityMatches");
```
函数调试名就是 `rawIdentityMatches`，**三字段严格全等**，不等即静默丢弃
（落入 `invalid external relay payload dropped`）。ack 的**接收**第一行就做这个比较。

**注意两条容易搞错的地方**（我第一遍就搞错了）：
1. schema 里 `bridgeGeneration`/`recoveryId` 是 `.optional()`（`out/main/chunk-OW6EUS2G.js` 偏移 547,331 的 `Dg`），
   所以**只带 `bridgeSessionId` 的 ack 能通过 schema 校验**——但**schema 可选 ≠ 运行时不比较**。
   比较是 `===`，"键缺失"（`undefined`）本身参与比较，只有对方也是 `undefined` 才相等。
2. 网页端自己的 ack 接收路径（主包 @6,364,869 `processAck(t.ackMessageSeq)`）确实不看 identity——
   但那是**网页端作为接收方**的路径；**桌面端作为接收方**走的是上面这个 `rawIdentityMatches`。
   两者不是同一段代码，别用前者否定后者。

**后果**：ack 被丢弃 ⇒ 桌面端重放缓冲里的帧永远收不到确认 ⇒ `ackGraceExceeded`（45 s，
`replayBufferGraceMs=45e3`）⇒ **终态降级**。我们 Rust 侧 `rpc_transport.rs:535-550` 的 ack
只带 `bridgeSessionId`（照抄 Dart），**必须补齐**。

**如果你要写 ack 相关结论，以本节为准**；若你发现与本节相反的证据，请给出坐标并明确说明。
