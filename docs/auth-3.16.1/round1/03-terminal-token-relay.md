# 03 · 终端 token 交换 / ws2 认证 / 配对与重连（ZCode Web 3.16.1 取证）

> 只读取证报告。未修改任何源码或快照。
> 坐标格式：`<文件>@<UTF-8 字节偏移>`，片段为从该偏移起 ≤200 字符的原文。
> 证据来源分三类，全文严格区分：
> - **【网页】** = `E:\Zcode_android\Reference_web\3.16.1\` 快照（web 终端客户端行为）
> - **【桌面】** = `C:\Users\wdn32\AppData\Local\Programs\ZCode\resources\app.asar`（桌面端 = device 侧行为）
> - **【本仓】** = 当前 Rust/Kotlin 实现
>
> **重要前提（必须先读）**：本机 `app.asar` 的 `package.json.version` = **3.16.3**，不是 3.16.1
> （`out/main/chunk-CJROBAMZ.js` 内亦有 `var Jr="3.16.3"`）。因此桌面端证据严格说是 **3.16.3** 的，
> 与 3.16.1 的差异未逐条 diff；但 relay/ws2 协议形状与网页 3.16.1 快照逐字一致（见 §4 schema 对比），
> 可视为同一契约。凡依赖"3.16.1 特有"的结论，本文只用网页证据。

---

## 1. 结论摘要

1. **ws2 的 `?token=` 不是 OAuth JWT，而是一次性换来的 22 字符短 token。** 终端先
   `POST /api/v1/remote/token`，`Authorization: Bearer <OAuth JWT>`，body `{"role":"terminal"}`，
   响应 `data.token` 必须匹配 `^[A-Za-z0-9_-]{21}[AQgw]$`。桌面端同一接口，`role:"device"`。
2. **token 每次建连都换，没有缓存。** 网页在 `connect()` 开头无条件 `await getToken()`；桌面在
   `connect()` 里同样每次 `await options.getToken()`。**当前 Android 实现只在 `ensureSession()` 换一次**，
   后续每次 reconnect 复用同一个短 token —— 这是最可能的线上故障源。
3. **`expires_in` 三家都只校验、不使用**：网页 schema 要求 `expires_in` 为正整数，但代码从不读它；
   桌面同；Kotlin 读出来存进 `RemoteToken(expiresInSecs)` 也没有任何续期逻辑。所以"过期"完全靠
   **每次重连重新换**来兜住，而不是靠主动刷新。
4. **`auth_init` 无 `role`**，字段只有 `type / device_sid / meta? / client_ts?`，`client_ts` 是 **Unix 秒**。
   当前 Rust 已按 token 模式发无 role 版本（CONFIRMED 对齐），legacy 分支仍发 `role:"terminal"`。
5. **`pair_status_query` 只允许 `{type, client_ts}`。** schema 是 `strictObject`；当前 Rust 多发一个
   `device_sid` —— 若服务端按同一 `strictObject` 解析，会被判 `WRONG_PARAM`。属高风险差异。
6. **`pair_status` 只有 `waiting` / `matched` 两个值**；客户端把 `matched` 映射成内部状态 `paired`，
   其余映射成 `waiting_terminal`。`terminal_sid` 是服务端在 `auth_ack` / `pair_status_ack` 里回给终端的
   会话标识，终端**从不发送**它。
7. **网页/桌面完全不解析 WebSocket 关闭码**（只记日志然后重连）。当前 Rust 的
   `4004/4009/4010/4011/4012/4013 → session-not-found/session-conflict/...` 映射在 3.16.1 网页与
   3.16.3 桌面里**找不到任何对应代码**，属旧基线遗留（UNVERIFIED）。
8. **ws2 升级阶段返回 401 的证据为零**：网页用浏览器 `WebSocket`，拿不到 HTTP 状态；桌面用 `ws`
   且没有 `unexpected-response` 监听。所有"401 条件"只能靠语义推断，全部标 UNVERIFIED/SPECULATION（见 §6）。
9. **网页 3.16.1 直接拒绝 legacy 链接**：URL 里出现 `hash` 或 `t` 任一个 → 判 `invalid-mobile-connection`
   并停在失败页。当前 Rust 仍保留 hash/t 的 HMAC 兼容路径。
10. **升级被拒（含 401）后网页/桌面都"永不终态"**：`onError` 是空实现，只有 `error`+`close(1006)` 和 15s
    握手超时 → 无限退避重连；网页每次重连都换新 token，所以"token 过期"表现为自愈式闪断，而"token 无效/
    角色错/mid 不符"表现为**永久重连且没有任何 401 文案**（§6.1）。
11. **两处本仓与网页相反**：waiting 超时本仓报 `invalid-mobile-connection`，网页报 `desktop-disconnected`（D15）；
    本仓多了网页没有的 20s Rewait 强制重连，而网页那套 stale-waiting 恢复是死代码（D16）。

---

## 2. 完整时序（终端视角）

```
[宿主 App]
  │  ① 用户已登录，持有 OAuth JWT（zcodejwttoken）
  │  ② 扫到链接 https://zcode.z.ai/remote/v4?sid=<device_sid>&mid=<device_mid>&name=…&app_version=…
  │     ├─ 网页：Ja(searchParams) 解析 → {deviceSid, deviceMid, deviceName, appVersion}
  │     │        若带 hash 或 t → 返回 null → 直接 invalid-mobile-connection（不再往下走）
  │     └─ 只带 sid、不带 mid 也允许（mid 只是 query 参数，客户端不强制）
  ▼
③ POST https://<origin>/api/v1/remote/token
   Authorization: Bearer <OAuth JWT>
   Content-Type: application/json
   {"role":"terminal"}
   ← 200 {"code":0,"msg":"...","data":{"token":"<22 chars>","expires_in":<int>}}
   ← 401 → auth-required（终态）
   ← 5xx → relay-unavailable（可重试）
   ← 其它 4xx → relay-unavailable（不可重试 → 终态）
  ▼
④ new WebSocket("wss://<origin>/api/v1/remote/ws2?token=<22 chars>&mid=<device_mid>")
   （网页 mid 仅在非空时追加；桌面无条件追加；无 subprotocol）
  ▼ open
⑤ → {"type":"auth_init","device_sid":"<device_sid>","meta":{"platform":…,"version":…,"name":…},"client_ts":<秒>}
  ▼
⑥ ← {"type":"auth_ack","device_sid":"<同一个 device_sid>","terminal_sid":"<服务端分配>","pair_status":"waiting"|"matched"}
   ├─ 网页：state 必须为 authenticating 且 ack.device_sid === 本地 deviceSid，否则整帧丢弃
   └─ 桌面：state 必须为 authenticating 且 ack.device_sid === 本地 deviceSid，否则整帧丢弃
  ▼
⑦ 心跳：每 5s(±1s 抖动) → {"type":"pair_status_query","client_ts":<秒>}
   ← {"type":"pair_status_ack","terminal_sid":…,"pair_status":"waiting"|"matched"}
   ├─ waiting  → state=waiting_terminal；首次配对起 30s 计时，超时 → desktop-disconnected（终态）
   └─ matched  → state=paired；清 waiting/rewait 定时器；重连计数归零；冲刷积压 payload
  ▼
⑧ paired 后：{"type":"data","payload":{…},"client_ts":<秒>}  ⇄  {"type":"data","payload":{…},"server_ts":<秒>}
   仅 state===paired 且已 authenticated 才收发。
  ▼
⑨ 断线：close → 立即 scheduleReconnect（网页：退避 500ms 起、上限 10s、无抖动；
   桌面：1s 起 ×2、上限 30s、抖动 0.2）→ **重新走 ③ 换新 token** → ④ …
```

**配对判定由服务端做，客户端只提供 `device_sid` 与 `mid`。** 客户端侧可见的约束只有两条：
(a) `auth_ack.device_sid` 必须回显请求的 `device_sid`；(b) `/api/v1/remote/device` 列表按 JWT 账号过滤。
其余（同账号 / 同 provider / 设备在线 / sid 匹配的具体判据）无源码，见 §9。

---

## 3. token 交换与 ws2 请求逐字段表

### 3.1 `POST /api/v1/remote/token`

| 项 | 网页【网页】 | 桌面【桌面】 | 证据 |
|---|---|---|---|
| URL 拼接 | `new URL(Er.token.path, origin)`，origin = `window.location.origin` | `new URL(d, e)`，`e` = `endpointOrigin` | index@6762730 / main/index.js@376941 |
| method | `POST` | `POST`（表里声明） | src@53641 |
| `Authorization` | `Bearer <OAuth JWT>` | `Bearer <OAuth JWT>` | 同上 |
| `Content-Type` | `application/json` | `application/json`（**所有方法都带**，含 GET） | 同上 |
| body | `{"role":"terminal"}` | `{"role":"device"}` | index@6762730 / main/index.js@377732 |
| 超时 | `AbortSignal.timeout(15000)` + 外部 signal | `AbortSignal.timeout(15e3)` + 外部 signal | 同上 |
| redirect | `redirect:'error'` | `redirect:'error'` | 同上 |
| 401 | `throw In('auth-required')` → 终态 | `throw Fe('auth-required')` | 同上 |
| 5xx | `In('relay-unavailable', true)` 可重试 | 同（`retryable = status>=500`） | 同上 |
| 其它非 2xx | `In('relay-unavailable', false)` → **不可重试 → 终态** | 同 | 同上 |
| 网络异常 | `In('relay-unavailable', true)` | 同 | 同上 |
| JSON 解析失败 | `relay-unavailable`（不可重试 → 终态） | 同 | 同上 |
| 响应校验 | `Nn('token', o)`：`code===0 && data` 才取 `data.token` | `sh('token', m)`：`code===0` 才取 `data.token` | 同上 |
| token 形状 | schema 正则 `^[A-Za-z0-9_-]{21}[AQgw]$`，不合规整帧判失败 | 同一正则 | src@53993 / chunk@815896 |
| `expires_in` | schema 要求 `int().positive()`，**代码不读** | 同，**代码不读** | 同上 |
| 缓存 | **无**。`connect()` 每次调用 `getToken()` | **无**。`connect()` 每次 `await options.getToken()` | index@6750492 / main/index.js@379911 |

`/api/v1/remote/token` 的请求/响应 schema（网页与桌面**逐字相同**）：
- 请求：`_({role: enum(['device','terminal'])})`（src@53858）
- 响应成功：`p({token: regex, expires_in: int().positive()})`（src@53993）
- 响应失败：`p({code: int().refine(e=>e!==0), msg: string, data: nullable.optional()})`（src@54078）
- 包装：`W(e) = union([{code:0,msg,data:e}, so])`（src@54078）

### 3.2 ws2 建连

| 项 | 网页【网页】 | 桌面【桌面】 | 证据 |
|---|---|---|---|
| URL 生成 | `Id({endpointOrigin})`：取 origin，`https→wss / 否则 ws`，`pathname='/api/v1/remote/ws2'` | `hK({endpointOrigin})`：同一算法 | validation@125195 / chunk@473404 |
| 默认 host | `https://zcode.z.ai`（`endpointOrigin` 为空时） | 同 | 同上 |
| 调用点 | `Qer(){return Wt({endpointOrigin:window.location.origin})}` | `ge?.relayWsUrl ?? e.relayWsUrl` | index@6818346 |
| `token` query | **无条件** `searchParams.set('token', n)` | **无条件** set | index@6750492 / main/index.js@379826 |
| `mid` query | `this.options.deviceMid?.trim() && set('mid', …)` —— **条件追加** | **无条件** `set('mid', this.options.deviceMid)` | 同上 |
| `mid` 缺失 | 不追加，照常建连 | 追加 `mid=undefined` 字符串（`URLSearchParams` 语义） | 同上 |
| query 编码 | `URLSearchParams`（`application/x-www-form-urlencoded` 规则：token 字符集 `[A-Za-z0-9_-]` 无需转义） | 同 | 同上 |
| subprotocol | 无（`new WebSocket(url)` 单参） | 无 | index@6750492 |
| 压缩 | 浏览器默认协商（Chromium 默认带 `permessage-deflate`）；客户端仅事后读取 `socket.extensions` 并写诊断 | 显式 `new WebSocket(url,{perMessageDeflate:!0})` | index@6751388 / main/index.js@379911 |
| 自定义 header | 无（浏览器不允许）；`Origin` 由浏览器自动填页面 origin | 无；`ws` 默认不发 Origin | 同上 |
| 握手超时 | 15s（`ho=15e3` → `mee`）→ `scheduleReconnect()` | 15s（`15e3`）→ `scheduleReconnect()` | index@6751103 / main/index.js@379911 |
| 帧大小上限 | `Qt.maxPhysicalFrameBytes` = `Fp.maxFrameBytes` = **1 MiB** | `xd.maxPhysicalFrameBytes` 同值 | validation@148178,160851 |

### 3.3 设备侧 `/api/v1/remote/device*`（桌面端注册）

| 端点 | method/path | 请求体 | 响应 data | 证据 |
|---|---|---|---|---|
| register | `POST /api/v1/remote/device` | `{device_mid}` | `{device_sid, device_mid, online, created_at, meta}` | src@53641 / src@53858 / chunk@815650 |
| list | `GET /api/v1/remote/device` | 无（`{}`） | 数组，元素同上 | 同上 |
| delete | `POST /api/v1/remote/device/delete` | `{device_sid}` | `null` | 同上 |
| token | `POST /api/v1/remote/token` | `{role}` | `{token, expires_in}` | 同上 |

桌面端注册逻辑（`main/index.js@405942`，`register` 闭包）：

1. 取当前登录 `{jwt,userId}` 与 `endpointOrigin`；
2. 读持久化 `deviceStorage`（`webRemoteControlRelayV2Device` 设置项，含 `{deviceSid,userId,endpointOrigin}`）；
3. **若 `!registrationPending && 缓存.userId===当前userId && 缓存.endpointOrigin===当前origin` → 直接复用 `deviceSid`**；
4. 否则：若旧记录同 origin → 先 `delete(oldDeviceSid)`；再 `register(deviceMid)` 拿新 `device_sid`；再 `save({deviceSid,userId,endpointOrigin})`；
5. 保存失败 → 回滚 `delete(newDeviceSid)` 并抛 `relay-unavailable`。
6. `registrationPending` 只由 `AUTH_FAILED`（且 state=authenticating 且未重试过）置真 → **强制重新注册**（`main/index.js@380905`）。

**链接里 `sid` / `mid` 与注册结果的关系**：`sid` = 上表 register 返回的 `device_sid`；`mid` = 客户端本机的稳定设备 id（`deviceMid`，与注册请求体同一个值）。QR 链接由
`qpe({baseUrl, deviceSid, deviceMid, deviceName, appVersion})`（chunk@813558）生成：

```js
function qpe({baseUrl:t,deviceSid:n,deviceMid:r,deviceName:a,appVersion:o}){
  let i=new URL(t);
  n!==void 0&&i.searchParams.set("sid",n);
  i.searchParams.delete("hash"); i.searchParams.delete("t");
  r?.trim()&&i.searchParams.set("mid",r);
  a?.trim()&&i.searchParams.set("name",a);
  o?.trim()&&i.searchParams.set("app_version",o);
  return i.toString()}
```
`baseUrl` = `ge?.remoteUrl`（= `${origin}/remote/v4`）。即 **3.16.1 起链接显式删除 `hash` 与 `t`**，
与网页端"见到 hash/t 即拒绝"（§7 差异 D1）互为闭环。

网页侧设备列表：`GET /api/v1/remote/device`，`Authorization: Bearer <OAuth JWT>`，`cache:'no-store'`，
15s 超时（`index@6747754`）。列表项按 `device_mid` 聚合成"设备"，`onlineConnections` 只收 `online:true`
的 `device_sid`（`L9n`）。用户选设备 → 把 `sid`/`mid`/`name` 写回页面 URL 再 `connect`。

---

## 4. 握手 / 配对帧 schema 表

网页（zod，`src-uVqBd_Eq.js`）与桌面（zod，`chunk-CJROBAMZ.js`）**逐字相同**，全部 `strictObject`
（多余字段即校验失败）。`Wt = int().min(0).max(253402300799)`（Unix 秒上限 9999-12-31）。

### 4.1 终端 → 服务端（`u('type', […])`，src@53028）

| type | 字段 | 类型 | 必填 | 网页实际发送 | 用途 |
|---|---|---|---|---|---|
| `auth_init` | `type` | `literal('auth_init')` | ✔ | ✔ | 帧类型 |
| | `device_sid` | `string`，1..128 且 **UTF-8 字节数 ≤128** | ✔ | ✔（来自链接 `sid`） | 声明要连接的设备会话 |
| | `meta` | `{name?, platform?, version?}` 全 optional | ✘ | ✔（网页硬编码内联） | 终端自身标识，服务端/桌面展示用 |
| | `client_ts` | `int` 0..253402300799 | ✘ | ✔（Unix **秒**） | 客户端时钟 |
| | `role` | **不存在** | — | **不发送** | 3.16.1 已移除（token 已表达角色） |
| `pair_status_query` | `type` | `literal('pair_status_query')` | ✔ | ✔ | 心跳 / 主动查询配对态 |
| | `client_ts` | `int` 秒 | ✘ | ✔ | 时钟 |
| | `device_sid` | **不存在** | — | **不发送** | ⚠️ 当前 Rust 多发（§7 D3） |
| `data` | `type` | `literal('data')` | ✔ | ✔ | 业务载荷 |
| | `payload` | `json`（任意 JSON） | ✔ | ✔ | RPC 帧 / app payload |
| | `client_ts` | `int` 秒 | ✘ | ✔ | 时钟 |

网页 `auth_init` 原文（`index@6751388`）：
```js
this.send({type:`auth_init`,device_sid:this.options.deviceSid,
  meta:{platform:`web`,version:this.options.appVersion??`web`,name:`mobile-browser`},
  client_ts:Math.floor(Date.now()/1e3)})
```
> 注意：网页**没有**使用功能更全的 `O9n()` meta 构造器（`index@6748451`，含 userAgent/viewport/screen/timezone/online），
> 那个只用于设备注册/遥测。`auth_init.meta` 就是上面这三字段的内联常量。

桌面 `auth_init` 原文（`main/index.js@380091`）：
```js
this.send({type:"auth_init",device_sid:n,meta:this.options.meta,client_ts:Math.floor(Date.now()/1e3)})
```
`options.meta` = `{platform:process.platform, version:e.appVersion, name:e.deviceName}`（`main/index.js@407266`）。

### 4.2 服务端 → 终端（`no` / `kB`，src@53240）

| type | 字段 | 类型 | 必填 | 客户端处理 |
|---|---|---|---|---|
| `auth_ack` | `type` | `literal('auth_ack')` | ✔ | 仅当 `state==='authenticating'` **且** `device_sid===本地deviceSid` 才接受 |
| | `device_sid` | 同 `Za`（≤128 字节） | ✔ | 同上；不匹配 → 整帧丢弃（不报错、不断连） |
| | `terminal_sid` | `string?` | ✘ | 客户端**从不读取**（仅存在于 schema） |
| | `pair_status` | `enum('waiting','matched')` | ✔ | 驱动状态机 |
| | `server_ts` | `int` 秒 | ✘ | 不读 |
| `pair_status_ack` | `type` | `literal('pair_status_ack')` | ✔ | 仅当 `authenticated` 才处理 |
| | `terminal_sid` | `string?` | ✘ | 不读 |
| | `pair_status` | `enum('waiting','matched')` | ✔ | 驱动状态机 |
| | `server_ts` | `int?` | ✘ | 不读 |
| `data` | `type` | `literal('data')` | ✔ | 仅当 `authenticated && state==='paired'` |
| | `payload` | `json` | ✔ | 转发给上层 |
| | `server_ts` | `int?` | ✘ | 不读 |
| `error` | `type` | `literal('error')` | ✔ | 见 §5 |
| | `code` | `enum('AUTH_FAILED','WRONG_PARAM','KICKED','INTERNAL','DEVICE_OFFLINE')` | ✔ | 严格五值，其它值整帧校验失败 → 丢弃 |
| | `message` | `string?` | ✘ | 作为 Failure message |
| | `server_ts` | `int?` | ✘ | 不读 |

`pair_status` → 内部状态映射（`src@54488`）：`matched → "paired"`，否则 `"waiting_terminal"`。
**没有第三种值**；`pair_status` 缺失或非字符串 → 两个分支都不命中，只更新 `lastPairStatusAckAt`。

### 4.3 `meta` 字段用途

| 字段 | 来源（网页） | 来源（桌面） | 用途 |
|---|---|---|---|
| `platform` | 常量 `"web"` | `process.platform`（`win32`/`darwin`/`linux`） | 服务端/桌面 UI 区分终端类型 |
| `version` | `appVersion ?? "web"`（URL `app_version`） | `e.appVersion` | 版本门控（桌面侧存在 `parseSemver` / `forceUpdate.minimalVersion` 比较逻辑） |
| `name` | 常量 `"mobile-browser"` | `e.deviceName` | 桌面端设备/终端展示名 |

**`meta` 是否参与服务端判定（二次复核）**：唯一把 `meta` 送上线的通道就是 `auth_init`——注册请求体是
`strictObject({device_mid})`（`chunk@815650`，**不含 meta**），而设备列表项 schema 却带
`meta:{name?,platform?,version?}`（`src@53913`）。列表 UI 读的正是 `meta.name` / `meta.platform` / `meta.version`
（`index@6767869` 的 `L9n`、`index@6790xxx` 的 `ver()`）。由此可推：**列表里的 meta 只能来自各连接的 `auth_init`**，
即 `meta` 至少参与"设备/终端展示"。是否参与配对判定（如平台白名单、最低版本）**无源码证据 → SPECULATION**。
本仓把 `name` 固定为 `"zemote"`（网页是 `"mobile-browser"`），若服务端对 `name` 有任何判定会偏差（低风险）。

---

## 5. 错误码与关闭码表

### 5.1 `error` 帧（服务端 → 终端）

| code | 网页行为【网页】 | 桌面行为【桌面】 | 终态? | 可重试? |
|---|---|---|---|---|
| `AUTH_FAILED` | `enterTerminalFailure('device-not-owned')`，`intentionallyClosed=true`，state=`error` | 若 `state==='authenticating'` 且未重试过 → `registrationPending=true` 并**立即重连**（强制重新注册拿新 device_sid）；否则 `handleConnectionError(relay-unavailable)`（不可重试 → state=error） | 网页是；桌面仅第二次是 | 桌面首轮是 |
| `WRONG_PARAM` | `enterTerminalFailure('invalid-mobile-connection')` | **无专门分支** → 落到 `handleConnectionError(new Fe('relay-unavailable'))` → 不可重试 → error | 是 | 否 |
| `KICKED` | `retry.kickedDelay()`：60s 内第二次 → `null` → `enterTerminalFailure('session-conflict')`（state=`kicked`）；否则延迟 2–5s 重连 | 同（`kickedDelay()` 语义一致：60s 窗口 + 2–5s 抖动） | 第二次是 | 第一次是 |
| `INTERNAL` | 若 `state==='paired'` 或 (`hadPairedSession && state==='waiting'`) → `enterWaitingForDeviceAfterRelayError`（**保 socket**、state=`waiting`、重启心跳）；否则 `recoverFromRelayInternal`（`immediate` 重连） | 一律 `scheduleReconnect()`（退避） | 否 | 是 |
| `DEVICE_OFFLINE` | `recoverFromDeviceOffline`：`onError` + 立即重连，并起 **15s** `desktopOfflineFailureTimer`，到点 → `enterTerminalFailure('desktop-disconnected')` | `scheduleReconnect()`（退避），**无终态计时** | 15s 后是 | 15s 内是 |
| 其它/未知 | zod 枚举校验失败 → 帧被丢弃（`parseIncoming` 返回 null），**完全不处理** | 同 | — | — |

原文（`index@6756293`）：
```js
handleRelayError(e,t){if(e===`KICKED`){let e=this.retry.kickedDelay();
e===null?this.enterTerminalFailure(H9(`session-conflict`)):this.scheduleReconnect({delayMs:e});return}
if(e===`DEVICE_OFFLINE`){this.recoverFromDeviceOffline(t||e);return}
if(e===`AUTH_FAILED`||e===`WRONG_PARAM`){this.enterTerminalFailure(H9(e===`AUTH_FAILED`?`device-not-owned`:`invalid-mobile-connection`));return}
if(e===`INTERNAL`){…}this.enterTerminalFailure(H9(`relay-unavailable`,t||e))}
```
（末尾的 `enterTerminalFailure('relay-unavailable')` 是**不可达**的兜底：`code` 已被 zod 限定为五值。）

### 5.2 关闭码

| 关闭码 | 网页【网页】 | 桌面【桌面】 | 当前 Rust【本仓】 |
|---|---|---|---|
| 任意 code（1006/1000/…） | 仅写诊断 `{code, reason, wasClean, wasPaired, state}`，然后**无条件** `scheduleReconnect()`（两个分支代码完全相同，`if(this.hadPairedSession){…return}` 是死分支） | 仅 `scheduleReconnect()`，另上报 `noteWebRemoteRelayConnectAttempt` 遥测 | `relay_close_reason()` 映射：4004 session-not-found / 4009 session-conflict / 4010 desktop-disconnected / 4011 session-expired / 4012 workspace-closed / **4013 invalid-mobile-connection** |
| 4013 | **源码中不存在**（`4013` 在 `out/main/index.js` 出现 0 次） | 同 | 映射为 `invalid-mobile-connection` |
| 未配对且未 `desktop-disconnected` | 仍重连 | 仍重连 | **不重连**：直接 `Error` + `Failure(mapped ?? relay-unavailable)`（`relay_client.rs@859`） |

> 结论：**关闭码在 3.16.1 网页 / 3.16.3 桌面的 relay 层没有任何语义**。Rust 的 4004/4009/… 映射来自
> 已归档的旧基线（`archive/pre-3.16.1-auth/`），当前权威源无法佐证 → 标 **UNVERIFIED（可能仍有效，但无 3.16.1 证据）**。

### 5.3 客户端自造的失败 reason

| reason | 触发（网页） | 触发（桌面） |
|---|---|---|
| `invalid-mobile-connection` | ① URL 带 `hash`/`t`；② `mr()` 解析返回 null；③ `WRONG_PARAM` | 无（`WRONG_PARAM` 无专门分支） |
| `device-not-owned` | `AUTH_FAILED` | 无（改用重新注册） |
| `session-conflict` | `KICKED` 在 60s 内第二次 | state 变 `kicked` → `Ye(Error("session-conflict"))` |
| `desktop-disconnected` | ① waiting 超时 30s；② `DEVICE_OFFLINE` 后 15s 宽限到期 | 无（只有 kicked） |
| `relay-unavailable` | 网络/5xx/解析失败/未知 error code | 同 |
| `auth-required` | token 或 device 列表返回 401 | 同 |

---

## 6. 401 的可能条件（专项分析）

**前提事实**：ws2 的 token 在 **query** 里，不在 header；两端客户端都**没有**能力读取升级响应的 HTTP 状态：
- 网页：`new WebSocket(url)`（`index@6750492`），浏览器 API 不暴露握手 HTTP 响应；失败只产生 `error` + `close(code=1006)`。
- 桌面：`new qa(i,{perMessageDeflate:!0})`（`main/index.js@379911`），`out/main/index.js` 内
  `unexpected-response` / `4013` 均出现 **0 次**；只有 `a.on("error")` 与 `a.on("close")`。

因此**任何"ws2 升级返回 401"的行为都无法从客户端源码证实**。逐条：

| # | 假设的 401 条件 | 证据 | 判定 |
|---|---|---|---|
| 1 | `token` 缺失 | 网页/桌面都无条件 `set('token', …)`；若 `getToken` 抛错则根本不会 `new WebSocket`。**客户端不可能发无 token 的请求** | 排除（客户端侧）；服务端行为 UNVERIFIED |
| 2 | `token` 格式非法（不匹配 `^[A-Za-z0-9_-]{21}[AQgw]$`） | 响应 schema 在**客户端**已用同一正则校验，非法 token 会在 HTTP 阶段就被丢弃（`relay-unavailable`），不会进入 ws2 | 客户端侧不可达；服务端行为 UNVERIFIED |
| 3 | `token` 过期 | `expires_in` 三家都不读；网页/桌面靠"每次重连重新换"规避。若服务端对过期 token 回 401，客户端只会看到 1006 并**换新 token 重连**（`connect()` 第一步就是 `getToken`），表现为"闪断后自愈" | **SPECULATION**（机制合理，无源码证据） |
| 4 | `token` 已被消费（一次性） | 无任何"消费"痕迹；同一 token 只用于一次连接是客户端的自然行为（每次重连都换），因此"一次性"与"过期"在客户端无法区分 | **SPECULATION** |
| 5 | `mid` 与 token 绑定不符 | 桌面每次重连都用**同一个** `deviceMid` + **新** token；网页用链接里的 `mid` + 新 token。若服务端把 token 绑到 mid，网页在"选了别的设备但 URL 未更新"时就会撞上——但 `selectDevice` 每次都会同步写 `sid`/`mid`（`index@6767630`：`n.searchParams.set('sid',t.device_sid),n.searchParams.set('mid',t.device_mid)`） | UNVERIFIED |
| 6 | 设备离线（桌面未连） | 有**明确**的协议内表达：`error{code:'DEVICE_OFFLINE'}`（不是 401）。网页还为此专门做了 15s 宽限 | **反证**：设备离线走 error 帧，不走 HTTP 401 |
| 7 | `sid` 失效（device 记录被删） | 桌面 `AUTH_FAILED` → **重新注册**（`registrationPending`）说明服务端确实可能"不认识这个 sid"，但该信号是 ws2 **升级成功后的 error 帧**，不是 401 | **反证**：走 error 帧 `AUTH_FAILED` |
| 8 | 账号 / provider 不匹配 | 网页 UI 文案 `"device-not-owned"` / `"该设备不属于当前账号"`，且 `AUTH_FAILED` 走 error 帧 | **反证**：走 error 帧 |
| 9 | `Origin` 不允许 | 网页 Origin 由浏览器填页面 origin；桌面不发 Origin。无 401 处理代码 | UNVERIFIED |
| 10 | 缺 `Authorization` header | 设计上 ws2 不用 header 认证，只用 query token | 排除 |

### 6.1 升级被拒之后：两端都**不会**进入终态（二次复核新增）

这条决定"如果服务端真的对 ws2 升级回 401，客户端会看到什么"：

| 端 | 升级失败（含 401/403）后的行为 | 是否终态 |
|---|---|---|
| 网页【网页】 | `error` 事件 → `onError`（`index@6823102` 处的 `onError:e=>{}`，**空实现**）；随后 `close` → `scheduleReconnect()`；15s `handshakeTimer` 也触发同一个 `scheduleReconnect()` | **永不终态**：500ms→10s 退避无限重连，每次重连都重新 `getToken()` 换新 token |
| 桌面【桌面】 | `a.on("error")` 只写日志；`a.on("close")` → `scheduleReconnect()`；**未监听** `unexpected-response`，拿不到握手 HTTP 状态 | **永不终态**（`error`/`kicked` 只由协议内 `error` 帧或不可重试的 token HTTP 失败触发） |
| 本仓【本仓】 | 由宿主 transport 报连接失败；`auth_token` 固定（D2），**不会**重新交换 | 取决于宿主；token 过期时**无法自愈** |

推论：网页侧一次持久的升级 401 表现为"无限重连 + 状态停在 reconnecting"，**不会**出现任何 401 文案，也不会
触发重新登录；唯一可观测痕迹是 `socket-error` / `socket-close{code:1006}` 诊断。这也解释了为什么 §5.3 里的
`session-not-found` / `session-expired` / `connection-recovery-timeout` 在 3.16.1 网页代码里**没有任何生产者**
（只出现在 i18n 文案表与桌面渲染层的原因映射 `index@2452891`）。

**可操作结论**：Android 侧**不应该**把"ws2 升级失败"当作 401 语义去处理，也不应假设存在可解析的 HTTP 状态；
正确做法是与网页/桌面一致 —— **升级失败即当作可重试的网络错误，重新换 token 重连**，并把
`AUTH_FAILED / WRONG_PARAM / DEVICE_OFFLINE / KICKED` 这四个**协议内 error 帧**作为唯一权威的语义信号。
若真机上确实观察到 401，那是服务端在升级阶段拒绝（客户端不可见），唯一可行的对策是"重换 token 再试"。

---

## 7. 与当前 Rust / Kotlin 实现差异清单

图例：`CONFIRMED` = 有 3.16.1/3.16.3 源码坐标可直接对比；`UNVERIFIED` = 无法从权威源确认；`SPECULATION` = 推断。

### D1 · legacy 链接处理 —— `CONFIRMED`
- 权威【网页】：`Ja(searchParams)` 见到 `hash` 或 `t` **任一存在就返回 null** → `invalid-mobile-connection` 终态
  （`src@51830`：`function Ja(e){if(e.has('hash')||e.has('t'))return null;…}`；`index@6766002`）。
- 权威【桌面】：生成的 QR 链接**主动 `delete('hash')` / `delete('t')`**（`chunk@813558`）。
- 本仓：`connection_params.rs@464-475` 保留 `pass_hash` / `timestamp` 为 `Option`，`relay_client.rs` 保留
  `auth_challenge` + HMAC proof 分支（`relay_client.rs@648-682`），`auth_init` 在 legacy 模式仍发 `role:"terminal"`（@579-585）。
- 影响：功能上更宽容（不会因旧链接直接失败），但**与 3.16.1 行为不一致**；若服务端已彻底下线 `/ws` 与
  `auth_challenge`，该分支是死代码（UNVERIFIED）。

### D2 · token 获取时机 / 缓存 / 刷新 —— `CONFIRMED`（最高优先级）
- 权威【网页】`index@6750492`：`connect()` 每次 `await this.options.getToken(t.signal)`；`closeSocket()` 会
  `tokenController?.abort()`。`N9n` 无缓存。
- 权威【桌面】`main/index.js@379911`：`connect()` 每次 `await this.options.getToken(t)`；`getToken` 每次都打
  `POST /remote/token`（`main/index.js@407338`）。
- 本仓：Kotlin `EnginePort.ensureSession()` 只在**会话创建/URL 或 JWT 变化**时换一次
  （`EnginePort.kt@272-308`、`resolveTerminalToken@316-325`），把结果塞进 `EngineConfig.token`；
  Rust `relay_ws_uri()` 用固定的 `params.auth_token`（`connection_params.rs@509`），**每次 reconnect 复用同一个 token**。
- 影响：长会话内 token 过期 → 每次重连都失败，且没有任何刷新路径。网页/桌面正是靠"每连必换"绕开这个问题。
- 建议方向（仅记录，不改码）：把 token 供给下沉到"每次 `do_connect` 前回调宿主换 token"，或至少在
  `RelayFailure(auth-*)` / 连续 N 次重连失败时触发一次重新交换。

### D3 · `pair_status_query` 多带 `device_sid` —— `CONFIRMED`（高风险）
- 权威：`src@53109` / `chunk@814591` = `strictObject({type:'pair_status_query', client_ts:int?})`，
  **没有** `device_sid`；网页发送 `{type, client_ts}`（`index@6757252`），桌面发送 `{type, client_ts}`（`main/index.js@378xxx`）。
- 本仓：`relay_client.rs@978-982` 与 `@1069-1073` 都发 `{type, device_sid, client_ts}`。
- 影响：若服务端用同一 `strictObject` 校验上行帧（大概率），每 10s 一次的心跳都会被判 `WRONG_PARAM`。
  这也可能是"连上后无反应"类故障的直接原因。

### D4 · 心跳周期与 ack 超时 —— `CONFIRMED`
- 权威【网页】`src@55634`（`xo` 心跳类）+ 常量 `G=5e3, mo=1e3, ho=15e3`：**间隔 5s ±1s 抖动，ack 超时 15s**。
- 权威【桌面】`chunk@816xxx`（`qh` 心跳类）+ `PB/UC`：同一默认（5s / 15s / 抖动上限 1000ms）。
- 本仓：`HEARTBEAT_INTERVAL_MS = 10_000`、`HEARTBEAT_ACK_TIMEOUT_MS = 30_000`（`relay_client.rs@138-140`），
  且 waiting 态隔拍发（20s）——这是旧 Dart 基线的值。
- 影响：服务端若有"心跳超时踢人"阈值，10s 可能仍在容差内；但 ack 超时 30s 会让掉线检测慢一倍。

### D5 · 重连退避 —— `CONFIRMED`
- 权威【网页】`An(Math.random,{initialMs:500,maxMs:1e4,jitter:0})`（`index@6749561`）→ 500ms 起、上限 10s、**无抖动**。
- 权威【桌面】`jC(...,{initialMs:1e3,maxMs:3e4,jitter:.2})`（`chunk@816634`）→ 1s 起 ×2、上限 30s、±20% 抖动。
- 本仓：`schedule_reconnect()` = `1000 * 2^clamp(attempt,0,4)`，clamp 到 1s..15s（`relay_client.rs@1094-1113`）。
- 影响：可接受（落在两端之间）；但与网页的"500ms 快速重试"不同，首连失败后感知更慢。

### D6 · `auth_ack` 校验强度 —— `CONFIRMED`
- 权威【网页】`index@6754826`：`state!=='authenticating' || ack.device_sid!==options.deviceSid` → **整帧丢弃**。
- 权威【桌面】`main/index.js@380091` 同语义。
- 本仓：`relay_client.rs@683` 把 `auth_ack` 与 `pair_status_ack` **合并处理**，既不检查 `state`，也不比对
  `device_sid`；收到 `auth_ack` 就直接 `apply_pair_status`。
- 影响：若服务端把 `auth_ack` 发给错误终端（或本端在错误状态收到），本仓会误判为已配对。安全性/正确性风险。

### D7 · `error` 帧语义映射 —— `CONFIRMED`
| code | 网页 | 桌面 | 本仓 |
|---|---|---|---|
| `AUTH_FAILED` | 终态 `device-not-owned` | 首次 → 重新注册 + 立即重连 | 终态 `auth-failed`（`relay_client.rs@777-793`） |
| `WRONG_PARAM` | 终态 `invalid-mobile-connection` | 落 `relay-unavailable` 终态 | 终态 `wrong-param` |
| `INTERNAL` | paired/waiting 时**保留 socket 转 waiting**；否则立即重连 | 退避重连 | **终态** `relay-internal`（最不宽容） |
| `DEVICE_OFFLINE` | 15s 宽限 → `desktop-disconnected` | 退避重连 | 只上报 `Failure(device_offline)`，保留重连（无宽限、无终态） |
| `KICKED` | 60s 内第二次 → `session-conflict`（kicked）；否则 2–5s 重连 | 同 | 额外引入 `message` 关键词启发式（`conflict/duplicate/already connected/another connection`）判定 transient（`@795-818`）；非 transient 直接 kicked |
- 影响：`INTERNAL` 在本仓被判终态，比网页/桌面都激进 —— 服务端偶发 `INTERNAL` 会导致会话直接死掉。

### D8 · 关闭码语义 —— `UNVERIFIED`
- 权威：网页/桌面 relay 层**完全不看 code**（§5.2）。
- 本仓：`relay_close_reason()` 映射 4004/4009/4010/4011/4012/4013，并据此决定"未配对时是否重连"。
- 影响：若服务端真的下发 4013/4010，本仓行为可能与网页不同（网页会一直重连）；但**无 3.16.1 证据**。

### D9 · `meta` 内容 —— `CONFIRMED`
- 权威【网页】`{platform:'web', version:'<app_version>||web', name:'mobile-browser'}`。
- 权威【桌面】`{platform:process.platform, version:appVersion, name:deviceName}`。
- 本仓：`DeviceInfo{platform: zemote_platform_name(...), version: app_version||"web", name: ZEMOTE_APP_NAME="zemote"}`
  （`device_info.rs@20,65,98`）。
- 影响：Android 上 `platform` 会是 `"android"`（合理，网页只在自己场景写 `"web"`）；`name` 固定 `"zemote"`，
  与网页的 `"mobile-browser"` 不同。若服务端按 `name` 做任何判定，需实证。**未见服务端读取 name 的证据 → 低风险。**

### D10 · token 响应校验强度 —— `CONFIRMED`
- 权威：正则 `^[A-Za-z0-9_-]{21}[AQgw]$` + `code===0 && data` + `expires_in` 必须正整数。
- 本仓 Kotlin `RemoteTokenClient.exchange`（`OAuth.kt@382-423`）：只判非空（**无正则**）；`code` 允许缺失
  （`code != null && code != 0` 才算错）；不要求 `expires_in`。
- 影响：宽松，不会误判；但也意味着"服务端返回 HTML 200 + 错误体"这类异常会被当成空 token 处理，报错文案较模糊。

### D11 · 设备列表 / 注册能力 —— `CONFIRMED`（能力缺失）
- 权威【网页】有完整设备列表 + 选择器（`GET /remote/device`，`E9n`），选设备后改写 URL。
- 本仓：无设备列表接口调用（仅靠扫码/粘贴链接）。
- 影响：功能差异，非协议错误。

### D12 · `terminal_sid` —— `CONFIRMED`（本仓缺失）
- 权威：`auth_ack` / `pair_status_ack` 都带 `terminal_sid?`，但两端客户端**都不读**（仅 schema 存在）。
- 本仓：Rust 收帧不解析该字段。
- 影响：无。可安全忽略（除非后续要用它做冲突检测）。

### D13 · relay 帧大小上限的**执行点** —— `CONFIRMED`
- 权威值：1 MiB（`Fp.maxFrameBytes = 1024*1024`，`validation@148178`；`_m.maxPhysicalFrameBytes`，`@160851`）。
- 权威执行点【网页】`index@6754592`：**每一帧**进来先算字节数，超 1 MiB 直接 `onRawTransportFault('remote.rpcFrame.envelopeTooLarge')` 并丢弃；
  桌面同（`main/index.js@379911` 内的 `c.byteLength > xd.maxPhysicalFrameBytes`）。
- 本仓：常量一致（`rpc_transport.rs:60 MAX_PHYSICAL_FRAME_BYTES = 1024*1024`），但 **`relay_client.rs` 内没有对
  relay 外层 JSON 帧做 1 MiB 门限**（该文件检索 `MAX_PHYSICAL_FRAME_BYTES` / `envelopeTooLarge` 均 0 命中），
  限制只落在 `rpc_transport` 的 base64 分片层。
- 影响：一个超大 relay 帧会先被完整 `serde_json::from_str` 再判类型，存在内存放大面；与网页"先量字节再解析"不同。

### D14 · token 请求的传输细节 —— `CONFIRMED`（低风险）
| 项 | 网页 | 本仓 Kotlin |
|---|---|---|
| `Accept` header | 不设（fetch 默认 `*/*`） | 显式 `Accept: application/json` |
| `redirect` | `'error'`（不跟随） | OkHttp 默认跟随 |
| 超时 | 单次 15s 总预算 | connect 20s / read 30s |
| `mid` 传给 token 接口 | 不传 | 不传（一致） |

### D15 · waiting 超时的 failure reason —— `CONFIRMED`（二次复核新增）
- 权威【网页】`index@6757479`：`enterTerminalFailure(H9('desktop-disconnected','Desktop did not match this mobile connection before the waiting timeout.'))`
  —— 30s 未配对 → **`desktop-disconnected`**。
- 本仓：`relay_client.rs` 的 `RelayTimer::Waiting` 分支发 **`invalid-mobile-connection`**（英文 message 与网页相同）。
- 影响：UI 显示"手机连接已失效/请重扫二维码"，而正确语义是"桌面端没有确认配对 / 桌面离线"。文案与引导动作都错。

### D16 · 网页两处"死代码"，本仓却实现了 —— `CONFIRMED`（二次复核新增）
- 权威【网页】`staleWaitingCount` 全文只被赋 `0` 或与 `>0` 比较（`index@6752799`），**从未自增**；
  `scheduleStaleWaitingRecovery()` 只有定义（`index@6757721`），**无任何调用点**。所以"paired 后收到 waiting
  → 计数 → 1.5s 后强制重连"这套 stale 恢复在 3.16.1 是**死代码**；实际行为只有 `applyPairStatus` 的
  `wasPaired` 分支："清定时器 + 转 waiting + 起心跳"（`index@6755486`）。
- 本仓：`apply_pair_status` 在 `was_paired && waiting` 时额外起 **20s Rewait 定时器**
  （`RECONNECT_WAIT_TIMEOUT_MS`，`relay_client.rs@745-752`），到点强制 `reconnect`。网页没有这个定时器。
- 影响：桌面端短暂回到 waiting 时，本仓会主动断链重连，比网页激进，可能与桌面端配对窗口竞争。

---

## 8. 证据索引

### 8.1 网页快照 `E:\Zcode_android\Reference_web\3.16.1\`

| # | 文件@字节偏移 | ≤200 字符原文 |
|---|---|---|
| W1 | `assets/src-uVqBd_Eq.js@51431` | `function Wa(e,t){return e.get(t)?.trim()\|\|void 0}` |
| W2 | `assets/src-uVqBd_Eq.js@51830` | `function Ja(e){if(e.has(\`hash\`)\|\|e.has(\`t\`))return null;let t={};for(let[n,r]of[[\`deviceSid\`,\`sid\`],[\`deviceMid\`,\`mid\`],[\`deviceName\`,\`name\`],[\`appVersion\`,\`app_version\`]]){…` |
| W3 | `assets/src-uVqBd_Eq.js@52667` | `H=m().int().min(0).max(253402300799),U=y().min(1).max(128).refine(e=>e.trim().length>0),Za=U.refine(e=>new TextEncoder().encode(e).length<=128),Qa=_({name:y().optional(),platform:y().optional(),version:…` |
| W4 | `assets/src-uVqBd_Eq.js@52886` | `$a=n([\`device\`,\`terminal\`]),eo=n([\`waiting\`,\`matched\`]),to=n([\`AUTH_FAILED\`,\`WRONG_PARAM\`,\`KICKED\`,\`INTERNAL\`,\`DEVICE_OFFLINE\`]);u(\`type\`,[_({type:g(\`auth_init\`),device_sid:Za,meta:Qa.optional(),…` |
| W5 | `assets/src-uVqBd_Eq.js@53028` | `type:g(\`auth_init\`),device_sid:Za,meta:Qa.optional(),client_ts:H.optional()}),_({type:g(\`pair_status_query\`),client_ts:H.optional()}),_({type:g(\`data\`),payload:te(),client_ts:H.optional()})]);var no=u…` |
| W6 | `assets/src-uVqBd_Eq.js@53240` | `type:g(\`auth_ack\`),device_sid:Za,terminal_sid:y().optional(),pair_status:eo,server_ts:H.optional()}),_({type:g(\`pair_status_ack\`),terminal_sid:y().optional(),pair_status:eo,server_ts:H.optional()}),_(…` |
| W7 | `assets/src-uVqBd_Eq.js@53497` | `type:g(\`error\`),code:to,message:y().optional(),server_ts:H.optional()})]);function ro(e){let t=no.safeParse(e);return t.success?t.data:null}var io={register:{method:\`POST\`,path:\`/api/v1/remote/device\`` |
| W8 | `assets/src-uVqBd_Eq.js@53641` | `io={register:{method:\`POST\`,path:\`/api/v1/remote/device\`},list:{method:\`GET\`,path:\`/api/v1/remote/device\`},delete:{method:\`POST\`,path:\`/api/v1/remote/device/delete\`},token:{method:\`POST\`,path:\`/api/v1…` |
| W9 | `assets/src-uVqBd_Eq.js@53858` | `_({device_mid:U}),_({}),_({device_sid:U}),_({role:$a});var ao=p({device_sid:Za,device_mid:U,online:f(),created_at:H,meta:p(Qa.shape)}),oo=p({token:y().regex(/^[A-Za-z0-9_-]{21}[AQgw]$/),expires_in:m()…` |
| W10 | `assets/src-uVqBd_Eq.js@53993` | `oo=p({token:y().regex(/^[A-Za-z0-9_-]{21}[AQgw]$/),expires_in:m().int().positive()}),so=p({code:m().int().refine(e=>e!==0),msg:y(),data:ne().optional()});function W(e){return d([p({code:g(0),msg:y(),d…` |
| W11 | `assets/src-uVqBd_Eq.js@54357` | `uo=class extends Error{constructor(e,t=!1){super(e),this.reason=e,this.retryable=t,this.name=\`WebRemoteControlV2ConnectionError\`}};function fo(e){return e===\`matched\`?\`paired\`:\`waiting_terminal\`}var p…` |
| W12 | `assets/src-uVqBd_Eq.js@54556` | `po=class{failures=0;lastKickAt;constructor(e=Math.random,t={initialMs:1e3,maxMs:3e4,jitter:.2}){this.random=e,this.backoff=t}acknowledged(){this.failures=0}nextDelay(){let e=Math.min(this.backoff.maxM…` |
| W13 | `assets/src-uVqBd_Eq.js@55634` | `xo=class{heartbeat;watchdog;constructor(e,t,n={}){this.query=e,this.expired=t,this.timing=n}acknowledged(){this.watchdog&&clearTimeout(this.watchdog),this.watchdog=setTimeout(this.expired,this.timing.…` |
| W14 | `assets/validation-BBf1FA1a.js@125195` | `function Id(e){let t=e?.overrideUrl?.trim();if(t)return t;let n=new URL(kd(e?.endpointOrigin?.trim()\|\|\`https://zcode.z.ai\`));return n.protocol=n.protocol===\`https:\`?\`wss:\`:\`ws:\`,n.pathname=\`/api/v1/re…` |
| W15 | `assets/validation-BBf1FA1a.js@126091` | `relayWsUrl:\`${i}/api/v1/remote/ws2\`,zcodePlanOpenAiBaseUrl:\`${n}/api/v1/zcode-plan\`,…` |
| W16 | `assets/validation-BBf1FA1a.js@148178` | `Fp={maxFrameBytes:1024*1024,logicalFrameAssemblyMaxBytes:16*1024*1024,logicalFrameAssemblyMaxFragments:1024,logicalFrameAssemblyMaxConcurrent:32,…` |
| W17 | `assets/validation-BBf1FA1a.js@160851` | `var _m={maxPhysicalFrameBytes:Fp.maxFrameBytes,maxMessageBytes:16*1024*1024,maxFragments:64,assemblyTimeoutMs:3e4,transportIdMaxChars:Fp.transportEnvelopeIdMaxChars},vm=…` |
| W18 | `assets/index-DbEHT6Gg.js@6747754` | `function E9n(e,t=fetch){return async(n,r)=>{try{let i=await t(new URL(Er.list.path,e),{method:\`GET\`,headers:{Authorization:\`Bearer ${n}\`},cache:\`no-store\`,signal:AbortSignal.any([r,AbortSignal.timeout…` |
| W19 | `assets/index-DbEHT6Gg.js@6749561` | `retry=new An(Math.random,{initialMs:500,maxMs:1e4,jitter:0});tokenController;handshakeTimer;authenticated=!1;heartbeat;socketGeneration=0;…` |
| W20 | `assets/index-DbEHT6Gg.js@6750492` | `async connect(){…let e=++this.socketGeneration;this.tokenController?.abort();let t=new AbortController;this.tokenController=t,this.authentic…` |
| W21 | `assets/index-DbEHT6Gg.js@6751103` | `this.handshakeTimer=setTimeout(()=>this.scheduleReconnect(),mee)`（`mee` = `ho` = `15e3`） |
| W22 | `assets/index-DbEHT6Gg.js@6751388` | `this.send({type:\`auth_init\`,device_sid:this.options.deviceSid,meta:{platform:\`web\`,version:this.options.appVersion??\`web\`,name:\`mobile-browser\`},client_ts:Math.floor(Date.now()/1e3)})` |
| W23 | `assets/index-DbEHT6Gg.js@6754592` | `if(v9n(e)>Qt.maxPhysicalFrameBytes){this.options.onRawTransportFault?.(\`remote.rpcFrame.envelopeTooLarge\`);return}` |
| W24 | `assets/index-DbEHT6Gg.js@6754826` | `case\`auth_ack\`:if(this.state!==\`authenticating\`\|\|n.device_sid!==this.options.deviceSid)return;this.authenticated=!0,clearTimeout(this.handshakeTimer),this.retry.acknowledged(),this.applyPairStatus(n.p…` |
| W25 | `assets/index-DbEHT6Gg.js@6756293` | `handleRelayError(e,t){if(e===\`KICKED\`){let e=this.retry.kickedDelay();e===null?this.enterTerminalFailure(H9(\`session-conflict\`)):this.scheduleReconnect({delayMs:e});return}if(e===\`DEVICE_OFFLINE\`){thi…` |
| W26 | `assets/index-DbEHT6Gg.js@6757252` | `sendPairStatusQuery(){return this.send({type:\`pair_status_query\`,client_ts:Math.floor(Date.now()/1e3)})}` |
| W27 | `assets/index-DbEHT6Gg.js@6757394` | `startWaitingTimer(){this.waitingTimer\|\|=setTimeout(()=>{this.state===\`waiting\`&&this.enterTerminalFailure(H9(\`desktop-disconnected\`,\`Desktop did not match this mobile connection before the waiting tim…` |
| W28 | `assets/index-DbEHT6Gg.js@6759435` | `recoverFromDeviceOffline(e){…this.desktopOfflineFailureTimer\|\|=setTimeout(()=>{…this.enterTerminalFailure(H9(\`desktop-disconnected\`,e))},this.options.desktopOfflineGraceMs??15e3),…` |
| W29 | `assets/index-DbEHT6Gg.js@6760849` | `enterTerminalFailure(e){this.intentionallyClosed=!0,…this.setState(e.reason===\`session-conflict\`?\`kicked\`:\`error\`),…this.options.onFailure(e),this.closeSocket()}` |
| W30 | `assets/index-DbEHT6Gg.js@6762730` | `function N9n(e,t=fetch){return async(n,r)=>{try{let i=await t(new URL(Er.token.path,e),{method:\`POST\`,headers:{Authorization:\`Bearer ${n}\`,"Content-Type":\`application/json\`},body:JSON.stringify({role:…` |
| W31 | `assets/index-DbEHT6Gg.js@6766002` | `let t=mr(e.searchParams);if(!t){this.options.onView({kind:\`failure\`,failure:{reason:\`invalid-mobile-connection\`}});return}if(!t.deviceSid){await this.openDevices(!0);return}` |
| W32 | `assets/index-DbEHT6Gg.js@6805474` | `devices:E9n(window.location.origin)` |
| W33 | `assets/index-DbEHT6Gg.js@6805737` | `token:N9n(window.location.origin)` |
| W34 | `assets/index-DbEHT6Gg.js@6818346` | `function Qer(){return Wt({endpointOrigin:window.location.origin})}`（`Wt` = validation 导出 `Id`，见 validation 导出表 `Id as rr` + index `import{… rr as Wt …}`） |
| W35 | `assets/index-DbEHT6Gg.js@6767630` | `selectDevice(e){…n.searchParams.set(\`sid\`,t.device_sid),n.searchParams.set(\`mid\`,t.device_mid);let r=t.meta.name?.trim();r?n.search…` |

### 8.2 桌面端 `app.asar`（**版本 3.16.3**）

| # | 文件@字节偏移 | ≤200 字符原文 |
|---|---|---|
| D-1 | `out/main/index.js@376941` | `function Zu(e,t=fetch){async function r(n,o,i,a){let{method:c,path:d}=oh[n];try{let l=await t(new URL(d,e),{method:c,headers:{Authorization:\`Bearer ${i}\`,"Content-Type":"application/json"},…` |
| D-2 | `out/main/index.js@377732` | `async token(n,o){let i=await r("token",{role:"device"},n,o);if(!i.data)throw new Fe("relay-unavailable");return i.data.token},async delete(n,o,i){await r("delete",{device_sid:n},o,i)}` |
| D-3 | `out/main/index.js@378125` | `var Os=class{constructor(t){this.options=t}options;static{s(this,"WebRemoteControlDeviceTransport")}socket;state="idle";…` |
| D-4 | `out/main/index.js@379826` | `i.searchParams.set("token",o),i.searchParams.set("mid",this.options.deviceMid);let a=new qa(i,{perMessageDeflate:!0});this.socket=a,this.handshakeTimer=setTimeout(()=>this.scheduleReconnect(),15e3)` |
| D-5 | `out/main/index.js@380091` | `this.send({type:"auth_init",device_sid:n,meta:this.options.meta,client_ts:Math.floor(Date.now()/1e3)})` |
| D-6 | `out/main/index.js@380905` | `if(t.code==="KICKED"){let r=this.retry.kickedDelay();r===null?(this.closeSocket(),this.setState("kicked")):this.scheduleReconnect(r)}else t.code==="AUTH_FAILED"&&this.state==="authenticating"&&!this.r…` |
| D-7 | `out/main/index.js@404839` | `function FA(h,b){return rh({baseUrl:b,deviceSid:h,deviceMid:e.deviceMid,deviceName:e.deviceName,appVersion:e.appVersion})}` |
| D-8 | `out/main/index.js@405942` | `Rd=s((ar,Mr)=>{let Ye=AbortSignal.any([ne,Mr]),Ko=o.then(async()=>{let Xr=await Gn(),Or=await e.deviceStorage.load();if(Ye.throwIfAborted(),!ar&&Or?.userId===Xr.userId&&Or.endpointOrigin===Dr)return O…` |
| D-9 | `out/main/index.js@406318` | `let ae=await zn.register(e.deviceMid,Xr.jwt,Ye);await Gn(),Ye.throwIfAborted();try{await e.deviceStorage.save({deviceSid:ae,userId:Xr.userId,endpointOrigin:Dr})}catch(ut){…` |
| D-10 | `out/main/index.js@407266` | `meta:{platform:process.platform,version:e.appVersion,name:e.deviceName},getToken:s(async ae=>{let ut=await Gn(),jo=await zn.token(ut.jwt,AbortSignal.any([ne,ae]));return await Gn(),…` |
| D-11 | `out/main/index.js@407526` | `resolveDeviceSid:s(async(ae,ut)=>(Ne=await Rd(ut,ae),t.get(h)===le&&(le.deviceSid=Ne,le.qrUrl=le.connectUrl=""),Ne),"resolveDeviceSid")` |
| D-12 | `out/main/chunk-CJROBAMZ.js@473404` | `function hK(t){let n=t?.overrideUrl?.trim();if(n)return n;let r=new URL(Ge(t?.endpointOrigin?.trim()\|\|un));return r.protocol=r.protocol==="https:"?"wss:":"ws:",r.pathname="/api/v1/remote/ws2",r.toStri…` |
| D-13 | `out/main/chunk-CJROBAMZ.js@813558` | `function qpe({baseUrl:t,deviceSid:n,deviceMid:r,deviceName:a,appVersion:o}){let i=new URL(t);return n!==void 0&&i.searchParams.set("sid",n),i.searchParams.delete("hash"),i.searchParams.delete("t"),r?.…` |
| D-14 | `out/main/chunk-CJROBAMZ.js@814232` | `MC=e.strictObject({name:e.string().optional(),platform:e.string().optional(),version:e.string().optional()}),mB=e.enum(["device","terminal"]),DC=e.enum(["waiting","matched"]),gB=…` |
| D-15 | `out/main/chunk-CJROBAMZ.js@814485` | `fB=e.strictObject({type:e.literal("auth_init"),device_sid:Vh,meta:MC.optional(),client_ts:Wt.optional()})` |
| D-16 | `out/main/chunk-CJROBAMZ.js@814591` | `hB=e.strictObject({type:e.literal("pair_status_query"),client_ts:Wt.optional()})` |
| D-17 | `out/main/chunk-CJROBAMZ.js@814801` | `bB=e.strictObject({type:e.literal("auth_ack"),device_sid:Vh,terminal_sid:e.string().optional(),pair_status:DC,server_ts:Wt.optional()}),SB=e.strictObject({type:e.literal("pair_status_ack"),termina…` |
| D-18 | `out/main/chunk-CJROBAMZ.js@815155` | `xB=e.strictObject({type:e.literal("error"),code:gB,message:e.string().optional(),server_ts:Wt.optional()})` |
| D-19 | `out/main/chunk-CJROBAMZ.js@815428` | `var tme={register:{method:"POST",path:"/api/v1/remote/device"},list:{method:"GET",path:"/api/v1/remote/device"},delete:{method:"POST",path:"/api/v1/remote/device/delete"},token:{method:"POST",path:"/a…` |
| D-20 | `out/main/chunk-CJROBAMZ.js@815650` | `var nme={register:e.strictObject({device_mid:Gs}),list:e.strictObject({}),delete:e.strictObject({device_sid:Gs}),token:e.strictObject({role:mB})},OC=e.object({device_sid:Vh,device_mid:Gs,online:e.bool…` |
| D-21 | `out/main/chunk-CJROBAMZ.js@815896` | `_B=e.object({token:e.string().regex(/^[A-Za-z0-9_-]{21}[AQgw]$/),expires_in:e.number().int().positive()}),CB=e.object({code:e.number().int().refine(t=>t!==0),msg:e.string(),data:e.null().optional()})` |
| D-22 | `out/main/chunk-CJROBAMZ.js@816634` | `function cme(t){return t==="matched"?"paired":"waiting_terminal"}c(cme,"webRemoteControlV2PairState");var jC=class{constructor(n=Math.random,r={initialMs:1e3,maxMs:3e4,jitter:.2}){…` |
| D-23 | `out/main/index.js`（全文检索） | `4013` 出现 0 次；`unexpected-response` 出现 0 次；`perMessageDeflate` 出现 1 次（`main/index.js@379911`） |

### 8.3 本仓实现

| # | 文件:行 | 要点 |
|---|---|---|
| R1 | `rust/crates/protocol/src/connection_params.rs:429-447` | `device_sid` / `pass_hash` / `timestamp` / `auth_token` / `device_mid` / `device_name` / `app_version` / `theme` / `source` |
| R2 | `rust/crates/protocol/src/connection_params.rs:460-483` | `parse()`：只要求 `sid` + scheme∈{https,wss}；hash/t 可选 |
| R3 | `rust/crates/protocol/src/connection_params.rs:490-520` | `relay_ws_uri()`：token 模式 `/api/v1/remote/ws2`，token 与 mid 都按 `Some` 条件追加 |
| R4 | `rust/crates/protocol/src/relay_client.rs:571-586` | `auth_init`：token 模式无 role，legacy 模式 `role:"terminal"` |
| R5 | `rust/crates/protocol/src/relay_client.rs:683-691` | `auth_ack`/`pair_status_ack` 合并处理，**不校验 state 与 device_sid** |
| R6 | `rust/crates/protocol/src/relay_client.rs:777-826` | `error` 映射：AUTH_FAILED/WRONG_PARAM/INTERNAL 终态；KICKED 关键词启发式；DEVICE_OFFLINE 仅上报 |
| R7 | `rust/crates/protocol/src/relay_client.rs:978-982`、`1069-1073` | `pair_status_query` **带 `device_sid`** |
| R8 | `rust/crates/protocol/src/relay_client.rs:125-135` | `relay_close_reason()`：4004/4009/4010/4011/4012/4013 |
| R9 | `rust/crates/protocol/src/relay_client.rs:138-151` | `HEARTBEAT_INTERVAL_MS=10s`、`ACK_TIMEOUT=30s`、`WAITING=30s`、`RECONNECT_WAIT=20s`、`DEAD_LINK=25s` |
| R10 | `rust/crates/protocol/src/relay_client.rs:1094-1113` | 退避 1s→15s（2^n，clamp 0..4） |
| R11 | `rust/crates/ffi/src/session.rs:463-500` | `config.token` → `params.auth_token`；非 dryRun 且无 hash/token → `BadInput` |
| R12 | `android/.../ui/EnginePort.kt:272-325` | `ensureSession` 快路径按 (url, jwt) 判等；`resolveTerminalToken` 在 `hasPassHash` 时返回 null |
| R13 | `android/.../auth/OAuth.kt:375-424` | `RemoteTokenClient.exchange`：POST `/api/v1/remote/token`，body `{"role":"terminal"}`，401→auth-required |
| R14 | `android/.../ui/AppContainer.kt:150` | `terminalTokenProvider = { host, jwt -> remoteTokenClient.exchange(host, jwt).token }` |

---

## 9. 未解问题与需要实证的清单

1. **`/api/v1/remote/token` 返回的短 token 有效期与是否一次性**：`expires_in` 数值未知；无任何客户端读取它。
   需抓包看真实 `expires_in`（怀疑 300–3600s 量级）。
2. **ws2 升级阶段是否会返回 HTTP 401**（以及条件）。客户端不可见（§6）。需用 `curl`/`websocat` 带过期 token
   直连 `wss://<host>/api/v1/remote/ws2?token=…&mid=…` 观察握手响应。
3. **服务端是否用 `strictObject` 校验上行帧**（决定 D3 的 `pair_status_query.device_sid` 是否必然触发
   `WRONG_PARAM`）。需真机抓一次 `WRONG_PARAM` 或反向验证。
4. **`matched` 的服务端判定条件**：同账号？同 provider？设备必须在线？`sid` 必须匹配已注册记录？
   客户端只提供 `sid`+`mid`+JWT，其余全在服务端。需服务端契约或黑盒实验（用他人账号的 sid 试连 → 预期
   `AUTH_FAILED`，可反证"同账号"必要条件）。
5. **`terminal_sid` 的实际用途**：两端客户端都不读。是否为"同设备多终端互踢"的句柄？未知。
6. **`INTERNAL` 的真实触发面**：网页把它当可恢复（转 waiting / 立即重连），本仓当终态。需知服务端在什么
   情况下发 `INTERNAL`，才能定策略。
7. **`DEVICE_OFFLINE` 与 15s 宽限**：网页有 15s 宽限后终态，桌面没有终态只重连，本仓只上报。三者三种策略，
   需实测哪种与线上服务端行为匹配。
8. **`mid` 缺失时服务端是否仍能配对**：网页允许不带 mid 建连（条件追加），但 `auth_init` 只带 `sid`。
   服务端是否用 query `mid` 做校验未知。需实测"去掉 mid"是否影响配对。
9. **`app_version` 门控**：桌面存在 `parseSemver` / `forceUpdate.minimalVersion` 比较；服务端是否对
   `auth_init.meta.version` 做最低版本校验（旧客户端被拒）未知。
10. **桌面端 3.16.3 与 3.16.1 的协议差异**：本轮未做逐字节 diff（快照只有 web 3.16.1）。若需严格 3.16.1
    桌面证据，需要 3.16.1 的 `app.asar`。
11. **`/api/v1/remote/device` 的 `online` 语义**：由 ws2 连接存在与否实时维护？离线多久变 false？
    web 设备列表依赖它做"在线设备"筛选，未验证。
12. **`hash`/`t` 旧链接的服务端兼容期**：网页 3.16.1 已拒绝，服务端 `/ws`（旧路径）是否仍在？
    决定 D1 的 legacy 分支是死代码还是仍有用户。
13. **网页在持续升级 401 下是否会永久重连**：按 §6.1 推导是"是"（无终态、无 401 文案）。需真机/抓包确认
    服务端是否在某次失败后改发 close code（此时才可能触发别的原因），以及重连是否被服务端限流。

---

## 10. 二次复核记录（独立重读，2026-10-26 第二遍）

本节只记录"独立重读权威源后新增/修正"的部分，用于区分本报告的两次取证。第一遍已给出的结论若未在此列出，
表示第二遍复核**未发现矛盾**（抽查项：token 每次建连重换、`^[A-Za-z0-9_-]{21}[AQgw]$`、`strictObject` 帧、
`error` 五值、30s waiting、15s `DEVICE_OFFLINE` 宽限、`Ja`/`mr` 拒绝 `hash`/`t`、桌面 `AUTH_FAILED` 重注册、
`4013` 在两端均 0 命中、app.asar 版本 `3.16.3`）。

**新增（第一遍未覆盖）**

| # | 结论 | 坐标 |
|---|---|---|
| N1 | 网页升级失败**永不终态**；`onError` 是空实现；15s 握手超时也走同一重连 | `index@6823102`（`onError:e=>{}`）、`index@6751043` |
| N2 | `session-not-found` / `session-expired` / `connection-recovery-timeout` 三个 reason **无生产者**（仅 i18n 与桌面渲染层映射） | `index@2452891`、`index@6773496`、`index@6776083` |
| N3 | 本仓 waiting 超时的 reason 是 `invalid-mobile-connection`，网页是 `desktop-disconnected` | `index@6757479` vs `relay_client.rs` Waiting 分支（D15） |
| N4 | 网页 `staleWaitingCount` 从不自增、`scheduleStaleWaitingRecovery` 无调用点 → 死代码；本仓的 20s Rewait 是网页没有的 | `index@6752799`、`index@6757721`（D16） |
| N5 | 注册请求体只有 `device_mid`，`meta` 只经 `auth_init` 上线；列表项 schema 却带 `meta` → 列表 meta 来自 `auth_init`（展示用途可证，判定用途 SPECULATION） | `chunk@815650`、`src@53937`、`index@6763661` |
| N6 | `mr`（index）= `Ja`（src）：`P as mr` 导入 + `Ja as P` 导出，D1 的解析器确为同一函数 | `src@51830`、导出表 |

**修正 / 收窄**

| # | 原表述 | 复核后 |
|---|---|---|
| C1 | §5.2 "桌面关闭时另上报 `noteWebRemoteRelayConnectAttempt` 遥测" | 未能在 3.16.3 `out/main` 内定位到该符号名；`close` 回调里确实有一次连接统计调用（`Al(...)`），**函数名 UNVERIFIED** |
| C2 | D4 "桌面心跳 `qh` + `PB/UC` 默认 5s/15s" | 仅确认常量与 `PB(t=5e3)`；类名 `qh` 未逐一核对（行为值已由 `chunk` 内 `PB/UC` 佐证，**低风险**） |
| C3 | §6 行 1 "客户端不可能发无 token 的请求" | 成立，但需补：网页 `getToken` 在 JWT 缺失时 `Promise.reject(In('auth-required'))`，同样不会建连（`index@6768808`；包装在 `index@6768719`） |

**仍未解决**：见 §9 的 13 条（第二遍新增第 13 条）。

