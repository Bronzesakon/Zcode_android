# ZCode 3.16.1 登录与信息流最终规格

**状态：** Round 2 的 A（入口/OAuth）、B（terminal token/relay）、C（数据流）、D（端到端一致性）
全部完成裁决。本文件已按四路交叉验证定稿。
**基线：** `Reference_web/3.16.1/`（网页，只读）。
**桌面证据：** 本机 `app.asar` 实测为 **3.16.3**，不是 3.16.1；凡标 `[桌面 3.16.3]` 的结论只能作为
「协议形状在 3.16.1→3.16.3 未变」的旁证，不能当作 3.16.1 桌面行为。
**旧基线：** `archive/pre-3.16.1-auth/` 只读历史证据，不作当前规格。

---

## 0. 结论先行

1. **登录是远控的前置条件。** 3.16.1 链接不再带 `hash`/`t`；手机必须先用 OAuth 登录 Z.ai 或
   BigModel 拿 JWT，再用 JWT 换 22 字符 terminal token，最后连
   `wss://<host>/api/v1/remote/ws2?token=<terminal token>&mid=<mid>`。
2. **ws2 的 `?token=` 不是 OAuth JWT。** 它是 `POST /api/v1/remote/token`
   （`Authorization: Bearer <JWT>`，body `{"role":"terminal"}`）换来的短 token；
   正则 `^[A-Za-z0-9_-]{21}[AQgw]$`。
3. **terminal token 每次建连都要重新换。** 网页 `connect()` 第一步就是 `await getToken()`；
   桌面同样。`expires_in` 只被 schema 校验，三端代码都不读；没有主动刷新，靠「每连必换」兜住。
4. **当前 Android 只在 `ensureSession()` 换一次，Rust 重连复用同一个 token。** 这是长会话/瞬时断线
   后 ws2 升级被拒（表现为 401/1006）的最可能根因；只有「终态重建」路径会重新换 token。
5. **`pair_status_query` 只能发 `{type, client_ts}`。** 网页与桌面 schema 都是 strictObject；
   当前 Rust 多发 `device_sid`，若服务端按同一 schema 校验上行帧，会每 10s 触发一次 `WRONG_PARAM`。
6. **`auth_ack` 必须先校验 `state === authenticating` 且 `device_sid === 本地 sid`**，否则整帧丢弃；
   `pair_status_ack` 只在 `authenticated` 后处理；`data` 只在 `authenticated && state === paired`
   时上抛。当前 Rust 三者都没做。
7. **心跳是 5s ±1s / ack 15s。** 当前 Rust 是 10s / 30s，且 ack 超时先发探针、下一拍才重连，
   有效检测约 40s。
8. **网页/桌面完全不解析 WebSocket 关闭码**，一律重连；Rust 的
   4004/4009/4010/4011/4012/4013 映射是旧基线遗留，且「未配对不重连」会让 401/1006 直接进终态。
9. **等待配对超时的 reason 是 `desktop-disconnected`**，不是 `invalid-mobile-connection`；
   前者引导「检查桌面端是否在线/确认配对」，后者错误地引导「重扫二维码」。
10. **登录后的任务列表不是 bootstrap 顶层 `tasks`。** bootstrap 只有 6 键；任务基底优先
    `projectService.readTree` + `controller.readActivities()`，无 controller 时才用 sessions-index；
    `pinned`/`archived`/`unreadAt` 不在 sessions-index 条目里。

---

## 1. 权威源与版本

| 源 | 版本 | 指纹/位置 | 用途 |
|---|---|---|---|
| 网页快照 | **3.16.1** | `index-DbEHT6Gg.js` 6,836,503 B / `dc0d8128…3c821` | 当前唯一权威规格 |
| 网页 chunk | 3.16.1 | `src-uVqBd_Eq.js` 70,714 B / `93813e7e…aaa91` | relay/token schema |
| 网页 chunk | 3.16.1 | `validation-BBf1FA1a.js` 331,153 B / `76868537…d8962` | zod schema、ws2 URL、bootstrap |
| 桌面 `app.asar` | **3.16.3** | `package.json` 第 58 行 `"version": "3.16.3"` | 只作旁证，不作 3.16.1 行为证明 |
| Android/Rust | 本仓 | `android/`、`rust/` | 差异对照 |

> 版本结论来自 A 路 §2-C4 与 D 路 §1.2：本机唯一 asar 是 3.16.3。Round 1 的 01/02 写「3.16.1 同发布」
> 是错的；03 写对了。

---

## 2. 入口、链接、门控

### 2.1 链接参数

解析器 `Ja()`（`src-uVqBd_Eq.js@51830`）：

```js
function Ja(e){
  if(e.has(`hash`)||e.has(`t`))return null;
  let t={};
  for(let[n,r]of[[`deviceSid`,`sid`],[`deviceMid`,`mid`],[`deviceName`,`name`],[`appVersion`,`app_version`]]){
    let i=Wa(e,r); i&&(t[n]=i)
  }
  return t
}
function Wa(e,t){return e.get(t)?.trim()||void 0}
```

| 参数 | 内部字段 | 必需 | 语义 |
|---|---|---|---|
| `sid` | `deviceSid` | 实质必需 | 缺失/空/纯空白 → 设备列表，不是错误 |
| `mid` | `deviceMid` | 可选 | relay `?mid=`；设备分组键 |
| `name` | `deviceName` | 可选 | 仅展示 |
| `app_version` | `appVersion` | 可选 | `auth_init.meta.version`；版本自愈比较 |
| `hash` / `t` | — | **禁止出现** | 任一存在（哪怕空值）→ `invalid-mobile-connection` 硬拒绝 |
| `theme` | — | 不读 | 网页远控路由不读 URL 的 theme |

补充：重复参数**首个胜**（`URLSearchParams.get`）；值 `trim()` 后为空即不存在；不做长度/字符集/host/scheme 校验。
`sid` 在 relay schema 里才要求 1..128 字符且 UTF-8 ≤128 字节。

### 2.2 门控顺序（`index-DbEHT6Gg.js@6765795`）

```
callback?  →  jwt?  →  parse params?  →  sid?  →  connect
```

1. 回调 URL（`pathname ∈ {/remote/v4, /remote/v4/}` 且有 `state` 且 `code|authCode|error`）→ 回调页；
2. 无 JWT → 登录页；
3. `Ja()` 返回 null（有 hash/t）→ `invalid-mobile-connection`；
4. 无 `sid` → 设备列表（`GET /api/v1/remote/device`，Bearer JWT）；
5. 有 `sid` → `connect()`。

### 2.3 版本

- 入口 HTML 的资源前缀写死 `/remote/v4/3.16.1/assets/...`（`index.html@10685`）；服务端按
  `app_version` 选 HTML 属**强推断、未直证**（UNVERIFIED）。
- 网页客户端唯一的版本动作：bootstrap 拿到 `desktopAppVersion` 后，若
  `pathname === '/remote/v4'` 且 `app_version !== desktopAppVersion`，把 `app_version` 改成桌面版本并整页
  `location.replace`（`index@6771185`）。页面未就绪立即跳；已就绪先弹版本不一致对话框。
- 桌面端用 semver `3.4.0` 决定生成 `/remote/v3` 还是 `/remote/v4`；网页端不做 semver 判断。

---

## 3. 登录（OAuth）

### 3.1 provider 与授权 URL

只有两个 provider，链接里**没有** provider 字段：

- `zai`（全球）：`https://chat.z.ai/api/oauth/authorize`
- `bigmodel`（中国）：`https://bigmodel.cn/login`

Z.ai（`index@6710973`）：

```
?redirect_uri=<enc redirect>&response_type=code&client_id=client_P8X5CMWmlaRO9gyO-KSqtg&state=<enc state>
```

BigModel：

```
?redirect=<enc redirect>&appId=zcode&state=<enc state>
```

无 PKCE（无 `code_challenge`/`code_verifier`）。

### 3.2 `state` 与 `app_return_to`

- `nonce = crypto.randomUUID()`（或 16B 随机 hex）；
- `state = base64url(JSON.stringify({nonce, app_return_to[, return_to]}))`，**无签名、无过期**；
- `app_return_to` = 当前页面 href（`e.appReturnTo ?? getCurrentHref()`）；
- `redirect_uri` 基值 `https://zcode.z.ai/remote/v4`；若当前 href 同源且 pathname ∈
  `/remote/v4(/), /remote/__test__(/)`，把 `app_version,sid,mid,name`（此顺序）复制到 redirect 上，
  去 fragment；
- pending 状态存 `sessionStorage`：`oauth_pending_nonce`、`oauth_pending_provider`、
  `oauth_pending_redirect_uri = JSON.stringify({nonce, redirectUri})`；
- 回调做 nonce **字符串全等**校验；不匹配 → `OAuth CSRF 检测失败`；
- **pending 在换 token 之前清**（`index@6720908`）；换 token 失败后同一回调 URL 重试会因 nonce 缺失失败，
  只能重新发起登录。

### 3.3 回调

- 只认 pathname `/remote/v4` 或 `/remote/v4/`，**不校验 host**；
- 必须带 `state` 且带 `code` / `authCode` / `error`；
- `authCode` **覆盖** `code`；
- 处理顺序：`parse → state 解码 → dev return_to → error → code → nonce 全等 → 取 pending
  provider/redirect → 清 pending → exchangeToken`；
- 成功后 `location.replace(C7n(app_return_to) ?? '/remote/v4')`；清洗规则 = 同源 + pathname 白名单 +
  只保留 `app_version,sid,mid,name`，丢弃 fragment；
- 正常路径回跳 URL 带 `sid`，重跑门控后**直接 connect**，不进设备列表。

### 3.4 token 交换

`POST /api/v1/oauth/token`（相对 origin）：

| 项 | 值 |
|---|---|
| Headers | 仅 `Content-Type: application/json`（无 Authorization） |
| Body | `{provider, code, redirect_uri, state}` |
| 成功判据 | `code === 0` + `data.token` 非空 + `data.<provider>.access_token` 非空 |
| `data.user` | 缺失/非法时回落 `{id:'unknown',username:'user',displayName:'User'}` |
| `data.expires_in` | 换算内存 `expiresAt`，**不落盘** |
| 错误 | 非 2xx → `OAuth token exchange failed with HTTP <status>`；`code!==0` → `msg` |

两平台唯一响应差异：`data.zai.access_token` / `data.bigmodel.access_token`。平台选错的实际拦截点就是
最后这个字段缺失。

### 3.5 凭据存储与过期

网页 localStorage 6 键（明文）：

| 键 | 值 |
|---|---|
| `oauth:active_provider` | `zai` / `bigmodel` |
| `zcodejwttoken` | `data.token`（JWT） |
| `oauth:zai:access_token` | Z.ai access_token |
| `oauth:zai:user_info` | `JSON.stringify(user)` |
| `oauth:bigmodel:access_token` | BigModel access_token |
| `oauth:bigmodel:user_info` | `JSON.stringify(user)` |

- 没有 refresh_token；没有跨标签页同步；
- 本地过期**只认 JWT `exp`**（30s 余量；`src@31023`）。`expires_in` 不参与拦截；
- JWT 过期/凭据不齐 → `clearAll()`，回到登录页；
- `logout()` = `clearAll()`，不动 sessionStorage pending；
- `saveTokenSet` 只写当前 provider 的键，不清理另一组。

**Android 当前差异（真实风险）：**

- `OAuth.kt` 把 `data.expires_in` 落盘并在 `AuthStore.token()` 当硬过期门（`AuthStore.kt:94`）；
  应改为 JWT `exp` + 服务端 401 兜底；
- `OAuth.kt` 的 `app_return_to` 填的是 `redirectUri`，不是当前 href；Android 用 WebView 拦截，暂无功能影响，
  但语义错位；
- `OAuth.kt` 的 `code ?: authCode` 与网页 `authCode` 覆盖 `code` 相反；
- `OAuth.kt` 只认 `name/email/user_id`，网页还认 `id/username/displayName` 驼峰；裸 base64 头像网页会补
  `data:image/png;base64,`，Android 不补；
- `isCallback` 额外要求 `https + host == zcode.z.ai`，比网页更严（对真实回调等价）；
- 无 JWT `exp` 本地校验；
- `redirect_uri` 白名单是否接受 `https://zcode.z.ai/remote/v4` 仍 UNVERIFIED（网页自己在用，强旁证）。

---

## 4. terminal token

### 4.1 请求

```
POST {origin}/api/v1/remote/token
Authorization: Bearer <OAuth JWT>
Content-Type: application/json
{"role":"terminal"}
```

- 超时 15s（`AbortSignal.timeout(15e3)` 与外部 signal `any`）；`redirect:'error'`；
- 桌面端同接口，body `{"role":"device"}`；
- 无 JWT 时不发请求，直接 `auth-required`。

### 4.2 响应与错误

```json
{"code":0,"msg":"...","data":{"token":"<22 chars>","expires_in":<positive int>}}
```

- token 正则 `^[A-Za-z0-9_-]{21}[AQgw]$`；
- `expires_in` 被 schema 强制但代码不读；
- 401 → `auth-required`（终态）；
- `>=500` → `relay-unavailable`（可重试）；
- 其它非 2xx / JSON 失败 / schema 不符 → `relay-unavailable`（不可重试，终态）；
- 网络异常 → `relay-unavailable`（可重试）。

### 4.3 调用时机（关键）

- 网页：`connect()` 每次 `await this.options.getToken(t.signal)`，然后才 `new WebSocket(...)`；
- 桌面：`connect()` 每次 `await options.getToken(t)`；
- **没有缓存、没有主动刷新**；`expires_in` 不参与；
- 当前 Android：`EnginePort.ensureSession()` 只在会话创建/URL 或 JWT 变化时换一次；Rust `do_connect`
  重连复用 `params.auth_token`。瞬时重连不会换 token；只有 `Error/Kicked/Closed` 终态重建才会。

### 4.4 修复方向（P0）

把 token 供给下沉到「每次 `WsConnect` 前」：

- 首次连接：沿用 `ensureSession()` 已换好的 token（避免双请求）；
- 后续每次 `WsConnect`（generation > 1）：宿主重新 `POST /api/v1/remote/token`，把新 token 替换进
  ws2 URI 后再开 socket；
- 换 token 失败：401 → 上报 `auth-required` 并引导重登；5xx/网络 → 按可重试失败处理，让核心退避重连；
- 不要根据 ws2 升级失败去猜 401；客户端拿不到握手 HTTP 状态。

---

## 5. ws2 与配对

### 5.1 URL

```
wss://<origin>/api/v1/remote/ws2?token=<22 chars>[&mid=<mid>]
```

- 网页 `mid` 仅 `trim()` 后非空才追加；桌面无条件追加；
- 无 subprotocol、无自定义 header、无 Authorization；
- 握手超时 15s；
- 升级失败（含 401/403）在网页/桌面客户端**结构性不可见**：只会得到 error + close(1006)；
  两端都按可重试网络错误无限退避重连，**永不终态**。

### 5.2 上行帧（strictObject，多一个键即非法）

```json
{"type":"auth_init","device_sid":"<≤128B>","meta":{"platform":"...","version":"...","name":"..."},"client_ts":<Unix 秒>}
{"type":"pair_status_query","client_ts":<Unix 秒>}
{"type":"data","payload":<任意 JSON>,"client_ts":<Unix 秒>}
```

- `auth_init` **无 `role`**；`client_ts` 是 Unix **秒**；
- `meta` 三字段都可选；网页值 `{platform:"web", version:appVersion??"web", name:"mobile-browser"}`；
  桌面 `{platform:process.platform, version:appVersion, name:deviceName}`；
  本仓 `{platform:"android", version:<宿主 appVersion>, name:"zemote"}`；
- `pair_status_query` **只能有 `type` 和 `client_ts`**；当前 Rust 多发的 `device_sid` 必须删除。

### 5.3 下行帧

| 帧 | 字段 | 客户端处理 |
|---|---|---|
| `auth_ack` | `device_sid`, `terminal_sid?`, `pair_status`, `server_ts?` | 仅当 `state === authenticating` 且 `device_sid === 本地` 才接受；接受后 `authenticated=true`、清握手定时器、重置重连计数 |
| `pair_status_ack` | `terminal_sid?`, `pair_status`, `server_ts?` | 仅当 `authenticated` 时处理 |
| `data` | `payload`, `server_ts?` | 仅当 `authenticated && state === paired` 才上抛 |
| `error` | `code`, `message?`, `server_ts?` | `code` 只有五值；未知值整帧丢弃 |

`pair_status` 只有 `waiting` / `matched`（必填）；映射 `matched → paired`，其余 → `waiting_terminal`。

### 5.4 状态机与定时器

- 心跳：`pair_status_query` 每 5s ±1s（抖动 `min(1000, interval*0.2)`）；ack 超时 15s；
- waiting 首次进入起 30s → `desktop-disconnected` 终态；
- `DEVICE_OFFLINE`：立即重连 + 15s 宽限；到点未 matched → `desktop-disconnected` 终态；
- `KICKED`：60s 窗口内第二次 → `session-conflict`（state=kicked）；否则 2–5s 抖动后重连；
- `INTERNAL`：已 paired（或 hadPairedSession 且 waiting）→ 保 socket 转 waiting 并重启心跳；否则立即重连；
- `AUTH_FAILED` → 终态 `device-not-owned`；`WRONG_PARAM` → 终态 `invalid-mobile-connection`；
- 退避：网页 500ms×2^n 上限 10s 无抖动；桌面 1s×2^n 上限 30s ±20%；
- 关闭码无语义：只写诊断，然后无条件 `scheduleReconnect()`。

### 5.5 当前 Rust 的偏差（按风险）

| 项 | 当前 | 3.16.1 | 风险 |
|---|---|---|---|
| `pair_status_query` | 多 `device_sid` | 只有 `type, client_ts` | 高 |
| token 刷新 | 重连复用 | 每连必换 | 高 |
| `auth_ack` | 与 `pair_status_ack` 合并，不校验 | 校验 state + device_sid | 高 |
| `data` 门禁 | 无 | `authenticated && paired` | 中 |
| waiting 超时 reason | `invalid-mobile-connection` | `desktop-disconnected` | 中高 |
| `INTERNAL` | 终态 | 可恢复 | 中 |
| 关闭码 | 映射并可能终态 | 不解析、一律重连 | 中 |
| 心跳 | 10s/30s + 探针 ≈40s | 5s/15s | 中 |
| `Rewait` | 20s 强制重连 | 网页无此机制（死代码） | 低中 |
| `AUTH_FAILED`/`WRONG_PARAM` reason | `auth-failed`/`wrong-param` | `device-not-owned`/`invalid-mobile-connection` | 低中 |
| 外层帧 1 MiB 门限 | 无 | 先量后解 | 低中 |
| 未配对出站队列 | 100 条静默入队 | 直接 unavailable | 低中 |

---

## 6. 配对后数据流（C 路返回后复核）

### 6.1 bootstrap

`bootstrap-response.result` 只有 6 键（`validation@304850`）：

```
windowControlSessionId
desktopAppVersion?
workspaces[]
initialViewState?
mobileViewState?
mobileTaskViewEpoch?
```

**没有顶层 `tasks`。** `workspace-list-response.result` = `{workspaces, activeWorkspaceKey?, activeTaskId?}`。

工作区元素：`workspacePath`(必) / `workspaceIdentity?` / `remoteSessionId?` / `label`(必) /
`workspacePurpose?` / `kind`(必) / `connectionState?` / `lastConnectionError?`。
键规则：`workspaceIdentity?.trim() || workspacePath`。

### 6.2 任务来源（重要）

任务不是单一来源，分三层：

1. **工作区层**：bootstrap 的 `workspaces`；
2. **任务基底**：`projectService.readTree({includeDeleted:true,includeDeletedTasks:true})`
   （每个远程 host 再来一次）+ `windowControllerService.readActivities()`；
   `zcodeTaskService.listPinnedTasks/listArchivedTasks/getTaskMeta` 提供元数据；
3. **活动覆盖**：sessions-index 的 `phase`/`lastActivityAt`/`hasBackgroundWork`/`pendingInteraction`/
   `workflowActivity`/`lastAssistantPreview`。

**C 路纠正：** 网页客户端的 `windowControllerService` 在服务容器里**无条件构造**，
所以 `Dvt(sessions-index)` 分支在网页客户端是**死代码**；任务基底恒为 `readTree`。
仍属 UNVERIFIED 的只是「远端 host 是否实现 `window-controller`/`project` RPC」。

`pinned` / `archived` **不在 sessions-index 条目 schema 里，也不在 `cvt` 的继承范围里**；
它们只来自 `readTree.tasks`（或 `zcodeTaskService.listPinnedTasks/listArchivedTasks`）。
`unreadAt` 来自 `getTaskMeta` / `onDynamicTaskUnreadSignal`；`cvt` 仅在 `previous.unreadAt` 为 truthy 时继承。
写回：controller 存在时首选 `window-controller.mutateTask({address,mutation})`；
`zcode-task` 的 flat 方法是 fallback（`setTaskPinned` / `setTaskUnread` / `archiveTask` 等）。

**Android 落地状态（2026-10-26）：**

已对齐：

- `WorkspaceProjection` 不再解析顶层 `tasks`，并保留 `activeWorkspaceKey/activeTaskId`（尚未用于落点）；
- `workspace-list-updated` 按 key 合并，保留本地 tasks；
- `SessionsIndexProjection` 不再从条目读 `pinned/archived/unreadAt`；`hasBackgroundWork` 独立于 `phase`；
- `markRead()` 已接到打开任务路径，带 `expectedUnreadAt`；
- sessions-index 的重复增量帧（`toSeq<=seq`）在 Rust 侧丢弃；
- 订阅 `completed` 注释已改为「wire 无 completed，Rust ack 后合成」。

仍待实现（缺权威 RPC 形状，先不猜）：

- `readTree` + `readActivities` 任务基底；`pinned/archived` 与 `getTaskMeta.unreadAt` 的权威来源；
- `mobileTaskViewEpoch` + `mobile-task-view-request/update`（需要 Rust 识别并上抛新事件）；
- 用条目 `workspaceId` 归位（宿主 `workspaceId` 与 `workspaceKey` 不可互推，需先拿到映射）。

### 6.3 sessions-index

- 通道 `zcode-agent`；方法 `subscribeSessionsIndexV4({workspacePath, workspaceIdentity?,
  runtimePolicy:'existing-only', base?, visibility?})` → `{ack:{subscriptionId, mode?, logEpoch?, openTiming?}}`；
  `mode==='resume' && ack.logEpoch===base.logEpoch` 才认为 base 已应用；
- 数据走事件 `onDynamicSessionsIndexFrame`，帧 `snapshot` / `deltas`，带 `toSeq`/`fromSeq`；
  回调还带 `deliveryKind`（`initial`/`recovery`/`online`），决定断档时走 `forceSnapshot` 还是 `requestRecovery`；
- `toSeq <= seq` 的重复帧直接丢弃；`fromSeq !== seq` 断档 → 首帧 `forceSnapshot`，否则
  `requestRecovery`；ack 前的帧先暂存，ack 后按序应用；
- 重试：subscribe 瞬时失败 `[250,1000,3000]ms`；错误恢复 `[5s,15s,60s]`；runtime restart →
  generation++、清 store、`forceSnapshot`；
- 条目 16 键：`sessionId`(=taskId) / `workspaceId` / `parentSessionId?` / `title` /
  `titleSource?` / `lastOutcome?` / `phase` / `sessionEnded` / `hasBackgroundWork` /
  `workflowActivity?` / `pendingInteraction?` / `pendingInteractionSummary?` / `goalStatus?` /
  `lastActivityAt` / `lastAssistantPreview?` / `createdAt`；
- `phase` 枚举：`draft` / `prewarming` / `running` / `completedSuccess` /
  `completedInterrupted` / `error`；
- 状态映射只看 `phase`：`running|prewarming → running`；`completedSuccess|completedInterrupted →
  completed`；`error → error`；`draft → 无`。`hasBackgroundWork` 是独立标记，**不并入** running。

### 6.4 `mobileTaskViewEpoch`

- 桌面每次进入 `paired` 时生成新 epoch，随 bootstrap 下发；
- 手机 `seed(epoch)`；桌面用 `mobile-task-view-request{epoch}` 索取；
- 手机回 `mobile-task-view-update{epoch, seq, bridgeSessionId, bridgeGeneration, view}`；
- 桌面校验：paired + epoch 匹配 + seq 单调 + bridge ready/未降级 + bridgeSessionId/bridgeGeneration
  匹配 + view.workspacePath/workspaceKey 匹配；
- 桌面据此判断「手机正在看哪个任务」，从而正确写 `unreadAt`。

当前 Android 未实现，导致桌面无法知道手机在看哪个任务，已读/未读行为不准。
另外 `mobile-view-state-update.deviceInfo` 是 12 键 schema 且 `updatedAt` 必填（缺它整帧被静默丢弃）；
Android 只发不收，bootstrap 的 `mobileViewState`/`initialViewState` 没有用于落点。

### 6.5 订阅 `completed` 的准确表述

- **wire**：`subscribeSessionsIndexV4` 只回 `{ack:{subscriptionId}}`，没有顶层 `completed`；
- **Rust FFI**：ack 到达后 `on_subscribe_response` 会 `emit_completion(flow, Completion::Ok)`，
  所以宿主 `conversationCall` 也能返回；
- `conversationCallNoWait` 仍是正确选择：订阅是长期事件流，用 `NoWait` 避免把 ack 延迟变成界面阻塞；
- 四处注释（`ZemoteEngine` / `EnginePort` / `ConversationRegistry` / `HomeViewModel`）已统一为
  「wire 没有 completed；Rust 在 ack 后合成 host-level Completed」。

---

## 7. ws2 升级 401 专题

### 7.1 源码能证明什么

- 网页用浏览器 `WebSocket`，拿不到握手 HTTP 状态；失败只产生 error + close(1006)；
- 桌面用 `ws`，没有监听 `unexpected-response`；`4013` 在 `out/main/index.js` 出现 0 次；
- 两端升级失败后都**不会终态**：无限退避重连；网页每次重连都重新换 token；
- 设备离线/账号不匹配/sid 失效实际走协议内 `error` 帧（`DEVICE_OFFLINE`/`AUTH_FAILED`），不是 401；
- 因此「ws2 升级 401」的精确条件无法从客户端源码证实，全部 UNVERIFIED/SPECULATION。

### 7.2 对 Android 的含义

- 不要把 ws2 升级失败当作可解析的 401 语义；
- 唯一正确对策：**每次重连重新换 terminal token**，然后按可重试网络错误退避；
- 如果换了新 token 仍持续 401，才需要怀疑服务端侧的 token 绑定/mid/sid/账号/provider 问题；
- 协议内 `error` 帧才是权威信号；不要用 close code 推断业务原因。

---

## 8. 实现差异矩阵（按风险排序）

| # | 主题 | 当前行为 | 3.16.1 行为 | 风险 | 动作 |
|---|---|---|---|---|---|
| R1 | `pair_status_query` 多 `device_sid` | `relay_client.rs` 行 979/1070 | 只有 `type, client_ts` | 高 | 删除 |
| R2 | token 只换一次 | `EnginePort.ensureSession` + Rust 复用 | 每连必换 | 高 | 每次 `WsConnect` 刷新 |
| R3 | `auth_ack` 不校验 | 合并处理，无 `authenticated` | 校验 state + device_sid | 高 | 拆分 + 校验 |
| R4 | waiting 超时 reason | `invalid-mobile-connection` | `desktop-disconnected` | 中高 | 改 reason + UI 文案 |
| R5 | `data` 无门禁 | 直接上抛 | `authenticated && paired` | 中 | 加门禁 |
| R6 | `INTERNAL` 终态 | Error + 关闭 | 可恢复 | 中 | 转 waiting/重连 |
| R7 | 关闭码映射 | 6 个码 + 未配对不重连 | 不解析、一律重连 | 中 | 删除映射/统一重连 |
| R8 | 心跳 10s/30s + 探针 | 有效 ~40s | 5s/15s | 中 | 对齐 5s/15s |
| R9 | 缺 `sid` 当非法 | `MISSING_SID` | 设备列表 | 中 | 至少区分错误码；设备列表产品决策 |
| R10 | legacy `hash`/`t` + HMAC | 接受 | 硬拒绝 | 中 | 默认 token 模式；legacy 收进开关 |
| R11 | `pinned`/`archived`/`unreadAt` | 从 sessions-index 读（恒空） | 任务服务/readTree | 中 | 接任务元数据通道 |
| R12 | `mobileTaskViewEpoch` | 未实现 | 有 | 中 | 实现 request/update |
| R13 | `expires_in` 硬过期门 | `AuthStore.token()` | JWT `exp` + 401 | 中 | 改 JWT exp |
| R14 | `KICKED` 关键词启发式 | 无 60s 窗口 | 60s + 2–5s | 低中 | 按 60s 窗口 |
| R15 | 外层帧无 1 MiB 门限 | 直接 serde | 先量后解 | 低中 | 加入口门限 |
| R16 | 未配对静默入队 | 100 条 | unavailable | 低中 | 评估是否改 |
| R17 | `Rewait` 20s | 有 | 网页无（死代码） | 低中 | 移除 |
| R18 | 顶层 `tasks` 死分支 | `WorkspaceProjection` 仍读 | 不存在 | 低 | 删掉 |
| R19 | `hasBackgroundWork` 并入 running | 是 | 只看 phase | 低 | 独立标记 |
| R20 | `app_return_to` 填 redirectUri | 是 | 当前 href | 低 | 语义修正 |
| R21 | 无版本自愈 | 无 | 有 | 低 | 记录/提示 |
| R22 | `meta.name="zemote"` | 是 | `mobile-browser` | 低 | 展示用途，可保持 |
| R23 | 无服务端设备列表 | 无 | 有 | 低（功能） | 产品决策 |
| R24 | `meta.version` 缺省 `"web"` | 一致 | 一致 | 无 | 保持 |
| R25 | 任务基底只读 sessions-index | 无 `readTree` | `readTree` + controller activities；`Dvt` 是死代码 | 中高 | 接 `readTree` / `zcodeTaskService` |
| R26 | `markRead` 死代码 | 只有定义，无调用 | 打开任务即标已读（带 `expectedUnreadAt`） | 中 | 接调用点 + 并发保护 |
| R27 | `workspace-list-updated` 清空 tasks | 整体替换 `workspaces` | 只更新 active 键，任务另行维护 | 中 | 合并而非替换 |
| R28 | 丢弃 `activeWorkspaceKey/activeTaskId` | `WorkspaceProjection.from` 只返回 workspaces | 用于落点选择 | 中 | 保留并用于 `Xa` |
| R29 | sessions-index 重复帧/主题 trim | 无重复帧丢弃；topic 不 trim | `toSeq<=seq` 丢弃；`lP` trim | 低 | 对齐 |
| R30 | `workspaceId` 归位 | 用 `workspace.key` | 条目里的 `workspaceId` | 低 | 对齐 |
| R31 | timeline 分组排除 pinned | `!archived && !pinned` | timeline 只排除 `archived` | 低 | 对齐 |
| R32 | `mobile-view-state-update.deviceInfo` | 只发不收；字段可能不全 | 12 键，`updatedAt` 必填 | 低中 | 补齐并读回 `mobileViewState` |

---

## 9. 修复检查清单

### P0（先做，直接对应 401/配对失败）

- [x] **每次重连换 terminal token**：Kotlin 宿主在 `WsConnect(generation > 1)` 时重新调用
      `RemoteTokenClient.exchange(host, jwt)`，替换 URI 的 `token` 参数后再开 socket；首次连接沿用
      `ensureSession` 的 token。失败：401 → `auth-required`；5xx/网络 → 可重试。
- [x] **删除 `pair_status_query` 的 `device_sid`**：`relay_client.rs` 心跳与 poke 两处；同步改测试。

### P1（配对正确性）

- [x] 增加 `authenticated: bool`；`do_connect` 时置 false；`auth_ack` 通过校验后置 true。
- [x] `auth_ack`：仅 `state == Authenticating` 且 `device_sid == params.device_sid` 时接受；否则整帧丢弃。
- [x] `pair_status_ack`：仅 `authenticated` 时处理。
- [x] `data`：仅 `authenticated && state == Paired` 时上抛。
- [x] 心跳 5s / ack 15s；去掉 waiting 隔拍；ack 超时直接重连（可保留 0–2s 抖动）。
- [x] waiting 超时 reason 改 `desktop-disconnected`；UI 文案改为「电脑端没有确认配对 / 桌面离线」。
- [x] 移除 `Rewait` 20s 强制重连。
- [x] 关闭码不再决定终态：`intentionally_closed` 之外一律 `schedule_reconnect`；映射只留日志。
- [x] `INTERNAL` 改为可恢复（paired/waiting 保 socket 转 waiting；否则重连）。
- [x] `AUTH_FAILED` reason 改 `device-not-owned`；`WRONG_PARAM` 改 `invalid-mobile-connection`；
      UI 文案与动作同步。

### P2（登录态与数据正确性）

- [x] `AuthStore.token()` 改用 JWT `exp`（30s 余量）+ 401 兜底；`expires_in` 只做诊断。
- [x] `OAuth.kt`：`authCode` 覆盖 `code`；头像裸 base64 补 `data:image/png;base64,`；user 归一化兼容驼峰。
      `app_return_to` 仍用 `REDIRECT_URI`：Android 没有网页回跳，回调由 WebView 直接消费。
- [x] `RemoteTokenClient`：校验 token 正则；`code` 必须为 0；`expires_in` 正整数（或至少诊断）。
- [x] `AuthStore.account` 与 `token()` 的过期语义一致。
- [x] 外层帧 1 MiB 门限（`on_frame` 入口，先量后解）。
- [ ] 接任务基底：`projectService.readTree` + `windowControllerService.readActivities()`；
      `pinned/archived` 从 `readTree.tasks` / `zcodeTaskService` 取，`unreadAt` 从 `getTaskMeta` 取。
- [x] `SessionsIndexProjection` 不再从条目读 `pinned/archived/unreadAt`；条目只提供活动/预览。
- [x] `markRead()` 接入打开任务路径，带 `expectedUnreadAt`；未读角标与自动展开才会工作。
- [ ] `mobileTaskViewEpoch` + `mobile-task-view-request/update`（含 epoch/seq/bridge 校验）。
- [x] `WorkspaceProjection` 删除顶层 `tasks` 死分支；`hasBackgroundWork` 独立标记。
- [x] `workspace-list-updated` 合并 active 键，不要整体替换掉本地 tasks。
- [ ] 保留 `activeWorkspaceKey/activeTaskId` 并用于落点；补 `mobileViewState` 读取。
- [x] sessions-index：丢弃 `toSeq<=seq` 重复帧；topic 用 `workspaceIdentity?.trim() || workspacePath`。
- [ ] `SessionsIndexProjection` 用条目 `workspaceId` 归位；timeline 只排除 `archived`。
- [x] 修正订阅 `completed` 注释（三处）。

### P3（兼容/体验）

- [ ] `hash`/`t`：默认拒绝；legacy `/ws` + HMAC 收进显式开关。
- [ ] 缺 `sid` 给出可区分错误码；设备列表产品决策。
- [ ] 版本自愈（`desktopAppVersion` 比对）。
- [ ] `KICKED` 60s 窗口 + 2–5s 抖动。
- [ ] `DEVICE_OFFLINE` 15s 宽限。
- [ ] 未配对出站队列行为评估。
- [ ] `meta.name` 是否改为 `mobile-browser`（低风险，待产品确认）。

### 测试要求

- Rust：`pair_status_query` 帧形状；`auth_ack` 校验（错 state/错 sid 丢弃）；`data` 门禁；
  心跳 5s/15s；waiting reason；`INTERNAL` 可恢复；关闭码一律重连；帧大小门限。
- Kotlin：`WsConnect` 第二次触发刷新；刷新 401 映射；刷新 5xx 可重试；URI token 替换；
  JWT `exp` 解析；头像 data URI；`authCode` 优先级。
- 回归：现有 375 个 Android 单测 + Rust workspace 全绿。

---

## 10. 未解问题

1. 服务端是否用同一套 strictObject 校验**上行**帧（决定 `pair_status_query.device_sid` 是否必然
   `WRONG_PARAM`）。客户端 schema 只能证明入站。
2. terminal token 是否一次性、`expires_in` 真实数值。三端都不读，无法区分「一次性」与「过期」。
3. ws2 升级 401 的精确条件（token 过期/被消费/mid 绑定/Origin）。客户端结构性不可见。
4. `terminal_sid` 的用途（三端 schema 有、代码都不读）。
5. `auth_init.meta.name/platform/version` 是否参与服务端配对或版本门控。
6. 远端 host 是否实现 `window-controller` / `project` RPC（服务对象在网页客户端恒存在；
   若 RPC 不可用，`readTree` 会失败，需要回退到 sessions-index）。
7. 服务端按 `app_version` 渲染 HTML 的回落策略（缺失/非法/不存在版本）。
8. `data.user` 真实字段形态；`avatar` 是 URL 还是裸 base64。
9. `redirect_uri` 白名单是否接受 `https://zcode.z.ai/remote/v4`（含任意 query）。
10. 3.16.1 桌面 asar 与 3.16.3 的逐字节差异（本机只有 3.16.3）。

---

## 11. 证据索引

- Round 1：
  - `round1/01-entry-and-gating.md`
  - `round1/02-oauth-login.md`
  - `round1/03-terminal-token-relay.md`
  - `round1/04-session-dataflow.md`
- Round 2：
  - `crosscheck/01-entry-oauth.md`（A）
  - `crosscheck/02-token-relay.md`（B）
  - `crosscheck/03-dataflow.md`（C，待返回）
  - `crosscheck/04-end-to-end.md`（D）
- 关键字偏移：`source-index.md`
- 旧基线撤销：`old-baseline-revocations.md`

**坐标约定：** 网页偏移是 UTF-8 字节偏移（`Buffer.indexOf`）；桌面 `tools/asar.mjs` 输出是 JS 字符串
char 偏移，不是字节偏移。两类不可混用。
