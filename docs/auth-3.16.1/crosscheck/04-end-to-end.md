# 04 · 交叉验证（D 路）：四份 Round 1 的端到端一致性 + 实现差异

> 目标：不信任 Round 1 的任何结论，独立重读 3.16.1 网页快照与 `rust/` / `android/`，专门找
> 「四份报告互相矛盾」「字段名对语义错」「旧基线残留」「网页/桌面证据混淆」，并给出最终可信结论与差异矩阵。
>
> 只读；未修改任何源码、未改动 Round 1 报告。未执行快照内 JS、未联网、未使用真机。
>
> **坐标约定（三类不可混用）**
> - 【网页 3.16.1】`Reference_web/3.16.1/assets/*`：**UTF-8 字节偏移**（`Buffer.indexOf` / `Buffer.byteLength` 实测）。
>   复核指纹：`index-DbEHT6Gg.js` 6,836,503 B / `dc0d8128b394d624…`；`src-uVqBd_Eq.js` 70,714 B；
>   `validation-BBf1FA1a.js` 331,153 B。**注意**：JS 字符串 `indexOf` 返回的是字符下标，主包含中文，
>   字符下标比字节偏移小约 12 万，二者不可互换（本报告一律用 Buffer 口径）。
> - 【桌面 3.16.3】`app.asar`：`tools/asar.mjs grep` 打印的是**解压后 JS 字符串的 char 偏移**，不是字节偏移。
> - 【本仓】`rust/`、`android/`：**UTF-8 字节偏移 + 行号**。
>
> **B 路状态**：`crosscheck/02-token-relay.md` 在本轮开始后落盘，已读，仅作旁证，不作为裁决依据。
> 本路独立复核后与其在 §1-10/11/12/14 上结论一致；差异点见 §6 末尾。

---

## 1. 裁决表（跨报告矛盾 → 裁决 + 坐标）

> 裁决枚举：`CONFIRMED` / `CONTRADICTED` / `MISSING` / `UNVERIFIED`。
> 一条矛盾若含可独立证伪的子断言，拆行裁决。
>
> **阅读提示**：下表「原文」列是从压缩产物里逐字抄出的，产物自身大量使用反引号（模板字符串/字面量），
> 因此单元格内的反引号是证据的一部分，Markdown 的行内代码渲染可能被内层反引号截断——请以原文为准。

### 1.1 ws2 路径：`/api/v1/remote/ws2` vs `/ws/remote/v4`

| # | 来源 | 断言 | 裁决 | 坐标 + 原文（≤300 字符） |
|---|---|---|---|---|
| 1a | 01 §5.3 / 03 §3.2 | 网页 relay WS 为 `wss://<host>/api/v1/remote/ws2` | **CONFIRMED** | 【网页】`validation-BBf1FA1a.js@125195` — `function Id(e){let t=e?.overrideUrl?.trim();if(t)return t;let n=new URL(kd(e?.endpointOrigin?.trim()||`https://zcode.z.ai`));return n.protocol=n.protocol===`https:`?`wss:`:`ws:`,n.pathname=`/api/v1/remote/ws2`,n.toString()}`；`/api/v1/remote/ws2` 字面量首字节 `125385` |
| 1b | 01 §7.2 | 网页调用点 `Qer(){return Wt({endpointOrigin:window.location.origin})}`，`Wt` 即 `Id` | **CONFIRMED** | 【网页】`index-DbEHT6Gg.js@6818346` — `function Qer(){return Wt({endpointOrigin:window.location.origin})}`；导入映射 `index@61298` — `rr as Wt`；导出映射 `validation@330795` — `Id as rr`。三者闭合证明 `Qer()` → `Id()` → `/api/v1/remote/ws2` |
| 1c | 04 §2 时序图第 41 行 / §5.4 | `wss://<origin>/ws/remote/v4?token=&mid=` | **CONTRADICTED** | **正确结论**：3.16.1 网页与 3.16.3 桌面都**不存在** `/ws/remote/v4` 这个 relay 路径。实测 `index-DbEHT6Gg.js` 中 `ws/remote/v4` 命中 **0** 次、`remote/ws2` 命中 **0** 次（ws2 在 `validation` chunk，不在主包）；`validation-BBf1FA1a.js` 中 `ws/remote/v4` 命中 **0** 次。`/ws/remote/` 只作为 **Web+Server 模式**（非远控）的路径出现：`index@6811083` — `?remote=<id>` → `/ws/remote/<id>`，与 relay 无关。**04 的坐标也错位**：其引用的 `index@6633951` 实际是测试夹具 `tdp05-session-…`（`index@6633951` — `r=`tdp05-session-${n}``），不是 WS 建连代码 |

### 1.2 桌面端版本：3.16.1 还是 3.16.3

| # | 来源 | 断言 | 裁决 | 坐标 + 原文 |
|---|---|---|---|---|
| 2a | 03 开篇前提 / B 路 | 本机 `app.asar` 是 **3.16.3** | **CONFIRMED** | 【桌面】`app.asar` 根 `package.json` 第 58 行 — `"version": "3.16.3"`；`out/main/chunk-CJROBAMZ.js` char@93 — `var Jr="3.16.3",oI="a82dd43e",rI="2026-10-23T02:41:37.939Z";` |
| 2b | 01 文首 / 02 文首 | 「桌面端 `app.asar`（3.16.1 同发布）」 | **CONTRADICTED** | **正确结论**：本机唯一的 `app.asar` 是 3.16.3。凡引用 asar 的结论（01 §7.5、02 §5.2/§7.4、03 §8.2、04 §8-E2/E6/E8）**只能标注为「桌面 3.16.3」**，不能标注 3.16.1。可保留的推论：3.16.3 的 relay 帧 schema 文本与 3.16.1 网页**逐字相同**（本轮复核 `fB/hB/bB` 与 `_({...})` 一致），故协议形状可跨版本使用；但「3.16.1 桌面行为」本身**无证据** |

### 1.3 `auth_init.meta.name`：Rust 与 web 是否一致

| # | 来源 | 断言 | 裁决 | 坐标 + 原文 |
|---|---|---|---|---|
| 3a | 03 §4.3 / §7-D9 | web = `mobile-browser`，Rust = `zemote`，二者不同 | **CONFIRMED** | 【网页】`index-DbEHT6Gg.js@6751388` — `this.send({type:`auth_init`,device_sid:this.options.deviceSid,meta:{platform:`web`,version:this.options.appVersion??`web`,name:`mobile-browser`},client_ts:Math.floor(Date.now()/1e3)})`；【本仓】`rust/crates/protocol/src/device_info.rs@1073` — `pub const ZEMOTE_APP_NAME: &str = "zemote";`；`relay_client.rs@15585` — `let device_info = DeviceInfo { platform, version: params.app_version.clone().unwrap_or_else(|| "web".to_string()), name: ZEMOTE_APP_NAME.to_string(), }` |
| 3b | 01 §6.3 | 「`auth_init` 的 `meta` … Rust 侧同名回落一致（`relay_client.rs:340`，CONFIRMED）」 | **CONTRADICTED** | **正确结论**：只有 `platform`/`version` 的**形状与回落规则**一致；`name` **恒不相等**（web `mobile-browser` vs Rust `zemote`）。且 `platform` 也不相等（web 常量 `web`，Android 实报 `android`，见 §1.15）。01 把「字段名相同」误判成「值相同」 |
| 3c | 03 §4.3 末 | `meta.name` 若被服务端判定会偏差（低风险） | **UNVERIFIED** | 服务端是否读 `meta.name` 无源码。可证的只有：桌面设备列表项 schema 带 `meta:{name?,platform?,version?}`（`src-uVqBd_Eq.js@53993` 区块 — `ao=p({device_sid:Za,device_mid:U,online:f(),created_at:H,meta:p(Qa.shape)})`），而注册请求体只有 `device_mid`，故列表 meta **只能**来自各连接 `auth_init`（推论，非直证） |

### 1.4 订阅是否发 `completed`

| # | 来源 | 断言 | 裁决 | 坐标 + 原文 |
|---|---|---|---|---|
| 4a | 04 §7.1 | Rust `on_subscribe_response` 成功后 `emit_completion(flow, Completion::Ok, events)` | **CONFIRMED** | 【本仓】`rust/crates/protocol/src/conversation_transport.rs@130806`（行 3174）— `emit_completion(flow, Completion::Ok, events);` |
| 4b | 04 §7.1 | `emit_completion` 只在 `flow.op.is_some()` 时发；`new_op_flow` 对 subscribe 也设 `op: Some(...)` | **CONFIRMED** | 【本仓】`conversation_transport.rs@146761`（行 3614）— `fn emit_completion(flow: &Flow, outcome: Completion, events: &mut Vec<ConversationEvent>) { if flow.op.is_some() { events.push(ConversationEvent::Completed {`；`@73305`（行 1790）— `op: Some(op.to_string()),`；`subscribeSessionsIndex` 走 `enqueue_flow` → `new_op_flow`（`@73921`，行 1804 — `let flow = self.new_op_flow(op, kind, stage);`） |
| 4c | `HomeViewModel.kt:407` / `ConversationRegistry.kt:71` / `ZemoteEngine.kt:397` 注释 | 「订阅类 op 没有 completed；等它只会挂到宿主兜底超时」 | **CONTRADICTED** | **正确结论**：该注释与 Rust 行为相反。Rust 在 ack 到达后**会**推 `Completed{Ok}`，`conversationCall` 也能返回。用 `NoWait` 本身无害（防呆），但注释必须改，否则会误导排障。坐标：`HomeViewModel.kt@16388`（行 407）— `// 订阅类 op 没有 completed；等它只会挂到宿主兜底超时。`；`ZemoteEngine.kt@18178`（行 397）— `**不会**再发顶层 `completed`（见 `conversation_transport.rs` 的` |
| 4d | 04 §7.1 引用的测试 | `sessions_index_subscription_uses_its_own_methods_and_args` 可证明「没有 completed」 | **CONTRADICTED** | 该测试（`conversation_transport.rs` 行 4560 起）只断言 `Subscribed{subscription_id:"si-1"}`、方法名 `subscribeSessionsIndexV4`、参数 `{workspacePath,workspaceIdentity,runtimePolicy:"existing-only"}`、`timer_start(&ev,10_000)==None`（无 watchdog），**没有**任何关于 `Completed` 的断言。注释引用它属误引 |

### 1.5 任务来源：`readTree` 还是 sessions-index

| # | 来源 | 断言 | 裁决 | 坐标 + 原文 |
|---|---|---|---|---|
| 5a | 04 §4.4 | 有 `windowControllerService` 时任务基底来自 `projectService.readTree`，活动来自 `controller.list/readActivities` | **CONFIRMED** | 【网页】`index-DbEHT6Gg.js@2025786` — `ovt(e)`：`let t=await e.remoteProjectService?.readDirectory()??{hosts:[],entries:[]},[n,r]=await Promise.all([e.projectService.readTree({includeDeleted:!0,includeDeletedTasks:!0}),Promise.allSettled(t.hosts.map(t=>e.projectService.readTree({hostId:t.id,includeDeleted:!0,includeDeletedTasks:!0})))])` |
| 5b | 04 §4.4 | sessions-index 投影 `Dvt` 只在**无** controller 时作为备选 | **CONFIRMED** | 【网页】`index@2058596` — `let I=Dvt(s?[]:j),…let e=U_t(p,s?s.readActivities():I.items.flatMap(e=>{let t=UO(e);…}))`。`s` 为 controller：有 controller 时 `Dvt([])` 为空、活动取 `s.readActivities()`；无 controller 时才用 `Dvt(j)`（j = sessions-index 任务）。两个分支都在源码里 |
| 5c | 03 §2 时序 / §4 | 任务「只用 sessions-index」 | **CONTRADICTED**（以偏概全） | **正确结论**：sessions-index 是**无 controller 分支**的基底，不是唯一来源；有 controller 时它只提供 `__zcodeSessionActivity`（预览/运行态）。03 未覆盖 `readTree` 分支 |
| 5d | 04 §9-U1 | 手机 Web 远端里 `windowControllerService` 是否可用 | **UNVERIFIED** | 【网页】该符号在 `index-DbEHT6Gg.js` 命中 11 次（首字节 `1842466`），说明客户端**会**装配它；但远端 host 是否实现 `list`/`readActivities` 需真机。**影响裁决**：若可用 → 权威路径是 `readTree + controller`；若不可用 → 权威路径退化为 `Dvt(sessions-index)`，即当前实现（`SessionsIndexProjection`）反而更接近线上行为。两种情况下**当前实现都不会崩**，差别只在「任务基底是否包含 sessions-index 未收录的本地任务」 |

### 1.6 `app_version` 的用途

| # | 来源 | 断言 | 裁决 | 坐标 + 原文 |
|---|---|---|---|---|
| 6a | 01 §5.5 | 服务端按 `app_version` 渲染对应版本 HTML，assets 前缀是其产物 | **UNVERIFIED**（强推断） | 快照只能证明「`entryUrl=…?app_version=3.16.1` 返回的 HTML 里写死 `/remote/v4/3.16.1/assets/...`」：`index.html@10685` — `/remote/v4/3.16.1/assets/index-DbEHT6Gg.js"></script>`；`_manifest.json` — `"entryUrl":"https://zcode.z.ai/remote/v4?app_version=3.16.1"`、`"assetPrefix":"/remote/v4/3.16.1/"`。**因果未直证**（无服务端源码/无对照版本快照） |
| 6b | 01 §5.4 | 桌面端用 semver 阈值 `3.4.0` 决定链接是 `/remote/v3` 还是 `/remote/v4` | **CONFIRMED**（桌面侧） | 【桌面】`out/main/chunk-CJROBAMZ.js` char@471323 — `var uE="3.4.0";function dE(t){…}` + `isWebRemoteControlV4AppVersion`；char@474879 区块 — `s=dE(n.appVersion)?"v4":"v3";return{…remoteUrl:`${r}/remote/${s}`,…}`。**网页客户端不做 semver 判断**，只做字符串相等（6c） |
| 6c | 01 §2.3 / 04 §3.1 | 客户端把 `desktopAppVersion` 与 URL 的 `app_version` 比对，不等则整页 replace | **CONFIRMED** | 【网页】`index@6771515` — `let s=a.desktopAppVersion?.trim();if(s){let t=new URL(e.getUrl());if(t.pathname.replace(/\/+$/,``)===`/remote/v4`&&t.searchParams.get(`app_version`)!==s){t.searchParams.set(`app_version`,s),r=t.toString(),e.isPageReady()?e.onMismatch(s):o();return}}` |
| 6d | 03 §4.3 / §7-D9 | `app_version` → `auth_init.meta.version`（缺省 `"web"`） | **CONFIRMED** | 【网页】`index@6751388`（同 3a）；【本仓】`relay_client.rs@15585`（同 3a）。桌面同形：`out/main/index.js` char@407266 — `meta:{platform:process.platform,version:e.appVersion,name:e.deviceName}` |
| 6e | 03 §9-9 暗示「服务端按 meta.version 做最低版本校验」 | 服务端用 `meta.version` 门控旧客户端 | **UNVERIFIED** | 桌面确有 `forceUpdate.minimalVersion` 的 semver 比较（`out/main/chunk-CJROBAMZ.js` char@819567 — `function $me(t){let n=t.forceUpdate?.minimalVersion.trim();…}`），但那是**桌面自身更新检查**，与 relay 的 `auth_init.meta.version` 无代码关联 |
| 6f | 01 §1.1 | 「客户端唯一的版本动作是自愈」 | **CONFIRMED** | 另有一处消费：`mobile-view-state-update.deviceInfo` 也带 `appVersion`：`index@6824321` — `deviceInfo:O9n({appVersion:t.appVersion})` |

**合并后的完整用途表**

| 用途 | 取值来源 | 消费方 | 证据 |
|---|---|---|---|
| 入口 HTML / assets 版本前缀 | URL `app_version` | 服务端（推断） | 6a（UNVERIFIED） |
| 桌面生成二维码时写链接 | 桌面自身 `appVersion` | 桌面 `qpe()` | 【桌面】`chunk-CJROBAMZ.js` char@813558 — `o?.trim()&&i.searchParams.set("app_version",o)` |
| 桌面决定 `/remote/v3` vs `/remote/v4` | 桌面自身 `appVersion` + `3.4.0` | 桌面 `Vd()` | 6b |
| `auth_init.meta.version` | URL `app_version`，缺省 `"web"` | relay/桌面展示（判定 UNVERIFIED） | 6d |
| `mobile-view-state-update.deviceInfo.version` | 同上 | 桌面 | 6f |
| 版本自愈（整页 replace） | `desktopAppVersion` vs URL | 网页客户端 | 6c |

### 1.7 OAuth `app_return_to`

| # | 来源 | 断言 | 裁决 | 坐标 + 原文 |
|---|---|---|---|---|
| 7a | 01 §4.3 / 02 §2[4] | web 的 `app_return_to` = **当前页面 href** | **CONFIRMED** | 【网页】`index@6719738` — `startLogin(e={}){let t=this.runtime.createNonce(),n=e.provider??`zai`,r=e.appReturnTo??this.runtime.getCurrentHref(),…let o=v7n({nonce:t,app_return_to:r,…})`；视图层注入 `index@6769046` 区块 — `startLogin({...this.options.loginOptions,...e?{provider:e}:{},appReturnTo:this.options.getHref()})` |
| 7b | 02 §6.1-D2 | Android 的 `app_return_to` 填的是 `redirectUri`（常量） | **CONFIRMED** | 【本仓】`android/app/src/main/kotlin/app/zemote/auth/OAuth.kt@5070`（行 143）— `val state = encodeState(nonce, redirectUri)`；`@7965`（行 201）— `fun encodeState(nonce: String, appReturnTo: String)` |
| 7c | 02 §6.1-D2 | 语义错位但「无功能影响」 | **UNVERIFIED** | `app_return_to` 在网页端是**回跳落点**（`location.replace(C7n(app_return_to) ?? '/remote/v4')`，`index@6803876`）；Android 用 WebView 拦截、不导航，因此该字段只作为**回显**被服务端原样带回，确实不驱动 Android 行为。但服务端是否校验其 host/path 无源码 → 只能标 UNVERIFIED |

### 1.8 `expires_in`

| # | 来源 | 断言 | 裁决 | 坐标 + 原文 |
|---|---|---|---|---|
| 8a | 02 §5.1 / §6.1-D9 | web 把 `expires_in` 算成**内存** `expiresAt`，不落盘 | **CONFIRMED** | 【网页】`index@6710263` — `function X5n(e,t){if(!(!e||!Number.isFinite(e)))return t()+e*1e3}`（`Z5n` 里 `let s=X5n(e.data?.expires_in,t);return{tokenSet:{accessToken:i,zcodeJwtToken:r,...s?{expiresAt:s}:{}},…}`）；`index@6712822` — `saveTokenSet(e,t){…this.localStorage.setItem(N9(t).accessToken,n),r?this.localStorage.setItem(M9,r):this.localStorage.removeItem(M9)}`（**只写 accessToken 与 jwt**）；`saveUserInfo` 只写 user_info |
| 8b | 02 §6.1-D9 / 03 §1.3 | Android 把 `expires_in` 落盘并当**硬过期门** | **CONFIRMED** | 【本仓】`OAuth.kt@13203`（行 333）— `val expiresAt = data["expires_in"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }?.let { nowMs + it * 1000 }`；`AuthStore.kt@3544`（行 94）— `if (stored.expiresAt != null && clock() >= stored.expiresAt) return@withContext null` |
| 8c | — | 裁决「正确做法」 | **CONTRADICTED（Android 侧）** | **正确做法**：web 的唯一本地过期判据是 **JWT 的 `exp`**（30s 余量）：`src-uVqBd_Eq.js@31023` — `function xr(e,t=Date.now(),n=3e4){…c.exp*1e3…t+Math.max(0,n)>=l?{kind:`expired`,expiresAt:l}:{kind:`valid`,expiresAt:l}…catch{return{kind:`unknown`}}`。`expires_in` 属于 OAuth **access_token** 的 TTL，与 zcode JWT 生命周期不同源，拿它当 JWT 的硬门会让长会话提前掉登录。**建议**：不再用 `expiresAt` 拦 `token()`，改为「JWT `exp` 校验 + 服务端 401 兜底」；`expires_in` 只留作诊断（语义仍 UNVERIFIED，需抓一次真实响应比对 `exp-iat`） |

### 1.9 关闭码 vs UI failure reason

| # | 来源 | 断言 | 裁决 | 坐标 + 原文 |
|---|---|---|---|---|
| 9a | 03 §5.2 | 网页/桌面 relay 层**不解析**关闭码，一律重连 | **CONFIRMED** | 【网页】`index@6751853` 区块 — `i.addEventListener(`close`,e=>{if(i===this.socket&&(this.emitDiagnostic({type:`socket-close`,code:e.code,reason:e.reason,wasClean:e.wasClean,wasPaired:this.wasPaired,state:this.state}),this.stopHeartbeat(),this.clearWaitingTimer(),this.clearStaleWaitingRecoveryTimer(),!this.intentionallyClosed&&this.state!==`suspended`)){if(this.hadPairedSession){this.scheduleReconnect();return}this.scheduleReconnect()}})` —— 两条分支体完全相同（死分支），`e.code` 只进诊断 |
| 9b | 03 §5.2 / §7-D8 | Rust 的 4004/4009/4010/4011/4012/4013 映射在 3.16.1/3.16.3 无对应代码 | **CONFIRMED** | 【本仓】`relay_client.rs@5230`（行 125）— `pub fn relay_close_reason(code: i32) -> Option<&'static str> { match code { 4004 => Some("session-not-found"), 4009 => Some("session-conflict"), 4010 => Some("desktop-disconnected"), 4011 => Some("session-expired"), 4012 => Some("workspace-closed"), 4013 => Some("invalid-mobile-connection"), _ => None } }`；且该映射**参与行为**：`relay_client.rs@37607`（行 859）— `if self.was_paired \|\| mapped == Some("desktop-disconnected") { self.schedule_reconnect(out); return; } self.set_state(RelayState::Error, out);`（未配对 + 非 4010 → 直接终态，网页不会） |
| 9c | 01 §4.4 失败表 | `session-not-found` / `session-expired` / `workspace-closed` 是 web 失败页的**触发点**（「终端失败原因」「桌面端无可用工作区」） | **CONTRADICTED** | **正确结论**：这三条在 3.16.1 网页终端里**没有生产者**，只是 i18n 文案表条目 + 桌面本地 UI 的消费映射。网页可证的 `enterTerminalFailure` **调用点只有 6 处**（字节 `6752358 / 6756377 / 6756579 / 6756872 / 6757479 / 6759715`；第 7 处 `6760849` 是函数定义本身），产生的 reason 仅：`auth-required`、`relay-unavailable`、`session-conflict`、`device-not-owned`、`invalid-mobile-connection`、`desktop-disconnected`。`session-not-found` 的 3 次命中分别是桌面渲染层消费映射（`index@2452891` — `switch(e.reason){case`session-not-found`:return t({id:`webRemoteControl.failure.sessionNotFound`})`）、zh-CN 文案（`index@6773150` 区块）、en-US 文案（`index@6777874` 区块） |
| 9d | — | 「关闭码」与「UI failure reason」是两个不同命名空间 | **CONFIRMED** | 关闭码是 WS `close(code)` 的整数（web 只记诊断）；failure reason 是字符串枚举（web 有 12 个文案键，其中 6 个无生产者）。Rust 把前者映射成后者的同名值（`4004→session-not-found`），这一步在 3.16.1/3.16.3 无任何依据 |

### 1.10 `pair_status_query` 的 `device_sid`

| # | 来源 | 断言 | 裁决 | 坐标 + 原文 |
|---|---|---|---|---|
| 10a | 03 §4.1 / §7-D3 | 网页 `pair_status_query` schema 是 `strictObject`，只允许 `{type, client_ts}` | **CONFIRMED** | 【网页】`src-uVqBd_Eq.js@53117` — `_({type:g(`pair_status_query`),client_ts:H.optional()})`；发送端 `index@6757252` — `sendPairStatusQuery(){return this.send({type:`pair_status_query`,client_ts:Math.floor(Date.now()/1e3)})}` |
| 10b | 03 §4.1 | 桌面同形 strictObject | **CONFIRMED**（3.16.3） | 【桌面】`out/main/chunk-CJROBAMZ.js` char@814591 — `hB=e.strictObject({type:e.literal("pair_status_query"),client_ts:Wt.optional()})`；发送端 char@378478 — `heartbeat=new ch(()=>this.send({type:"pair_status_query",client_ts:Math.floor(Date.now()/1e3)}),()=>this.scheduleReconnect());` |
| 10c | 03 §7-D3 | Rust 多发 `device_sid` | **CONFIRMED** | 【本仓】`relay_client.rs@42313`（行 979，心跳）— `json!({ "type": "pair_status_query", "device_sid": self.params.device_sid.clone(), "client_ts": self.wire_client_ts(now_ms), })`；（行 1070，poke）同形 |
| 10d | 03 §7-D3 | 「若服务端按同一 strictObject 解析，会判 `WRONG_PARAM`」 | **UNVERIFIED** | 客户端 schema 只证明**入站**校验；服务端是否用同一 schema 校验**上行**帧在源码中不可见。B 路（§1-5b）独立得出同一结论 |

### 1.11 心跳

| # | 来源 | 断言 | 裁决 | 坐标 + 原文 |
|---|---|---|---|---|
| 11a | 03 §3.2 / §7-D4 | 网页 5s ±1s，ack 超时 15s | **CONFIRMED** | 【网页】`src-uVqBd_Eq.js@55069` — `G=5e3,mo=1e3,ho=15e3,go=2e3`；`src@55643` — `acknowledged(){…this.watchdog=setTimeout(this.expired,this.timing.ackTimeoutMs??15e3),…}schedule(){this.heartbeat=setTimeout(()=>{…this.query(),this.schedule()},yo(this.timing.intervalMs??5e3,this.timing.jitterMs??(this.timing.intervalMs===void 0?1e3:void 0)))}`；抖动上限 `vo` = `min(1e3, floor(interval*0.2))` → 5s 时正好 ±1s |
| 11b | 03 §7-D4 | 桌面同默认（5s / 1s 抖动 / 15s） | **CONFIRMED**（3.16.3） | 【桌面】`chunk-CJROBAMZ.js` char@818327 — `UC(this.timing.intervalMs??5e3,this.timing.jitterMs??(this.timing.intervalMs===void 0?1e3:void 0))`；char@818066 — `setTimeout(this.expired,this.timing.ackTimeoutMs??15e3)` |
| 11c | 03 §7-D4 | Rust 10s / 30s，且 waiting 态隔拍（20s） | **CONFIRMED** | 【本仓】`relay_client.rs@5684`（行 138）— `pub const HEARTBEAT_INTERVAL_MS: i64 = 10_000;`、`HEARTBEAT_ACK_TIMEOUT_MS: i64 = 30_000;`；`@41500`（行 958）— `if self.state == RelayState::Waiting && self.heartbeat_tick % 2 == 1 { return; }` |
| 11d | 03 §7-D4 | 「ack 超时 30s 让掉线检测慢一倍」 | **CONTRADICTED**（数值偏差） | **正确结论**：Rust 的 30s 不是直接重连，而是**两拍**：先发一次探针，下一拍才重连 → 有效检测约 `30s + 10s ≈ 40s`，相对网页 15s 是 **≈2.7 倍**。坐标：`relay_client.rs@41609`（行 961）— `if now_ms - self.last_pair_status_ack_at > HEARTBEAT_ACK_TIMEOUT_MS { if !self.stale_probe_sent { self.stale_probe_sent = true; … } else { … self.reconnect(transport, now_ms, out); return; } }`（B 路 M5 独立发现同一偏差） |

### 1.12 `hash` / `t`

| # | 来源 | 断言 | 裁决 | 坐标 + 原文 |
|---|---|---|---|---|
| 12a | 01 §3 / 03 §7-D1 | 网页见到 `hash` 或 `t` 任一即 `null` → `invalid-mobile-connection` | **CONFIRMED** | 【网页】`src-uVqBd_Eq.js@51830` — `function Ja(e){if(e.has(`hash`)\|\|e.has(`t`))return null;let t={};for(let[n,r]of[[`deviceSid`,`sid`],[`deviceMid`,`mid`],[`deviceName`,`name`],[`appVersion`,`app_version`]]){let i=Wa(e,r);i&&(t[n]=i)}return t}`；调用点 `index@6766002` — `let t=mr(e.searchParams);if(!t){this.options.onView({kind:`failure`,failure:{reason:`invalid-mobile-connection`}});return}` |
| 12b | 03 §7-D1 | Rust 仍保留 `pass_hash` / `timestamp` 与 legacy HMAC | **CONFIRMED** | 【本仓】`connection_params.rs@20674`（行 463）— `let device_sid = get_param(&uri, "sid")?; let pass_hash = get_param(&uri, "hash"); let timestamp = match get_param(&uri, "t") { Some(raw) => Some(parse_int_like_dart(&raw)?), None => None };`；`relay_client.rs@28250`（行 648）— `Some("auth_challenge") => {`；legacy `auth_init` 仍发 `role:"terminal"`：`relay_client.rs@25368`（行 581） |
| 12c | — | 是否应对齐 | **CONFIRMED（应删除/隔离）** | 3.16.1 网页**硬拒绝**、桌面生成的链接主动 `delete("hash")`/`delete("t")`（`chunk-CJROBAMZ.js` char@813558），两端闭环。Rust 的 legacy 分支是旧 Dart 基线；若服务端已下线 `/ws` + `auth_challenge`，它是死代码；若仍在线，则 Android 会接受网页明确判死的链接，行为分叉。**建议**：默认走 token 模式；legacy 收进显式开关并加「不产生新功能」的注释 |

### 1.13 `sid` 缺失

| # | 来源 | 断言 | 裁决 | 坐标 + 原文 |
|---|---|---|---|---|
| 13a | 01 §4.1 | web 缺 `sid` **不是错误**，是「设备列表」分支 | **CONFIRMED** | 【网页】`index@6766124` — `if(!t.deviceSid){await this.openDevices(!0);return}`（`t` 为 `Ja` 结果；`sid=`/`sid=%20%20` 经 `Wa` trim 后为空也走这里） |
| 13b | 01 §6.1-D7 / 03 | Rust 把缺 `sid` 当非法链接；Kotlin `MISSING_SID` | **CONFIRMED** | 【本仓】`connection_params.rs@20674` — `let device_sid = get_param(&uri, "sid")?;`（`?` 即 `None`）；`session.rs@22095`（行 468）— `BadInput::new(format!("url 解析失败（需要 https/wss 且含 sid）: {url}"))`；`PairingUrl.kt@3712`（行 63）— `RejectReason.MISSING_SID)` |
| 13c | — | 影响与是否需要设备列表 | **CONFIRMED** | 影响：用户拿到「只有域名/只带 mid」的入口时，网页会进设备列表并可自动选中（`G9n` 用 `last-device-sid/mid`），Android 直接报错，只能重扫码。**Android 现有的 `ui/devices` 是本地已保存设备列表（`DeviceRepository`/`DeviceStore`），不调 `GET /api/v1/remote/device`**（`grep api/v1/remote/device android/` 0 命中），因此无法复刻网页的「服务端设备列表 + 自动选中」。是否补：产品决策；协议上不补也能用（只要链接带 sid） |

### 1.14 `auth_ack` 校验

| # | 来源 | 断言 | 裁决 | 坐标 + 原文 |
|---|---|---|---|---|
| 14a | 03 §7-D6 | web 要求 `state==='authenticating'` 且 `ack.device_sid===本地`，否则整帧丢弃 | **CONFIRMED** | 【网页】`index@6754826` — `case`auth_ack`:if(this.state!==`authenticating`\|\|n.device_sid!==this.options.deviceSid)return;this.authenticated=!0,clearTimeout(this.handshakeTimer),this.retry.acknowledged(),this.applyPairStatus(n.pair_status);break;`；`pair_status_ack` 另有 `if(!this.authenticated\|\|this.state===`suspended`)return;` |
| 14b | 03 §7-D6 | 桌面同语义 | **CONFIRMED**（3.16.3） | 【桌面】`out/main/index.js` char@381403 — `if(t.type==="auth_ack"){if(this.state!=="authenticating"\|\|t.device_sid!==this.deviceSid)return;clearTimeout(this.handshakeTimer),this.authenticated=!0,this.retry.acknowledged()}`（注意：03 引用的 char@380091 是 `auth_init` 发送处，坐标错位；语义本身正确） |
| 14c | 03 §7-D6 | Rust 把 `auth_ack`/`pair_status_ack` 合并，不校验 `state` 也不比对 `device_sid` | **CONFIRMED** | 【本仓】`relay_client.rs@30211`（行 683）— `Some("auth_ack") \| Some("pair_status_ack") => { let status = frame.get("pair_status")…; self.apply_pair_status(status.as_deref(), transport, now_ms, out); }`；Rust 全仓无 `authenticated` 字段，`data` 帧也无 `paired` 门槛（`relay_client.rs` 行 692 区块） |

### 1.15 `meta.name` / `platform` / `version` 三列终值

| 端 | `platform` | `version` | `name` | 证据 |
|---|---|---|---|---|
| 网页 3.16.1 | `"web"`（常量） | `app_version ?? "web"` | `"mobile-browser"`（常量） | `index@6751388`（同 3a） |
| 桌面 3.16.3 | `process.platform`（`win32`/`darwin`/`linux`） | `appVersion` | `deviceName`（用户可见设备名） | `out/main/index.js` char@407266 — `meta:{platform:process.platform,version:e.appVersion,name:e.deviceName}` |
| 本仓 Rust/Kotlin | `"android"`（`zemote_platform_name(false, Android)`；Kotlin 注入 `targetPlatform="android"`,`isWeb=false`） | `app_version ?? "web"` | `"zemote"`（`ZEMOTE_APP_NAME`） | `device_info.rs@1073`；`relay_client.rs@15585`；`android/.../engine/EngineModels.kt`（行 298-300）— `val isWeb: Boolean = false, val targetPlatform: String = "android"` |

裁决：**CONFIRMED**（三端形状相同、值不同）。`platform`/`version` 的回落规则与网页一致；`name` 三端全不同。

### 1.16 裁决统计

| 裁决 | 条数 |
|---|---:|
| CONFIRMED | 35 |
| CONTRADICTED | 9 |
| UNVERIFIED | 6 |
| MISSING | 0（本轮 15 项均有覆盖；补漏见 §3-R8 / §5-X8） |

共 50 行裁决（15 个矛盾项拆成可独立证伪的子断言）。9 条 CONTRADICTED：1c（ws2 路径）、2b（桌面版本）、3b（meta 一致性）、4c（订阅 completed 注释）、4d（误引测试）、5c（任务来源以偏概全）、8c（expires_in 硬门）、9c（三个 reason 无生产者）、11d（40s 不是 30s）。

---

## 2. 最终可信结论集（入口 → 登录 → terminal token → ws2 配对 → 数据流）

> 每条一句话 + 坐标。可直接作为实现依据。

### 2.1 入口与链接

1. 链接只认 4 个参数：`sid→deviceSid`、`mid→deviceMid`、`name→deviceName`、`app_version→appVersion`，取值 `trim()` 后空即不存在，无格式校验。`src-uVqBd_Eq.js@51830`。
2. `hash` 或 `t` **任一存在**（哪怕空值）即判 `invalid-mobile-connection`，是硬拒绝不是降级。`index@6766002`。
3. 缺 `sid` 不是错误，进「设备列表」分支；`sid` 缺失/空白/纯空格都走这里。`index@6766124`。
4. 门控顺序固定：`callback?` → `jwt?` → `params?` → `sid?` → `connect`。`index@6766002` 区块。
5. 入口 HTML 的 assets 前缀写死版本（`/remote/v4/3.16.1/assets/...`），服务端按 `app_version` 选版本属**强推断未直证**。`index.html@10685`。

### 2.2 登录

6. 只有两个 provider（`zai` / `bigmodel`），链接里**没有** provider 字段，也没有自动探测。`src-uVqBd_Eq.js@51830`（解析器无该键）。
7. 授权 URL：Z.ai `…/api/oauth/authorize?redirect_uri&response_type=code&client_id=client_P8X5CMWmlaRO9gyO-KSqtg&state`；BigModel `…/login?redirect&appId=zcode&state`。`index@6710973`。
8. `state` = 无签名 `base64url(JSON{nonce, app_return_to[, return_to]})`；`nonce` 只存 `sessionStorage`，回调做字符串全等。`index@6719738` / `index@6720237`。
9. `app_return_to` = **当前页面 href**（`e.appReturnTo ?? getCurrentHref()`），不是 redirectUri。`index@6719738`。
10. `redirect_uri` 会把 `app_version,sid,mid,name` 白名单参数从 `app_return_to` 搬上去，因此回跳后连接参数自动恢复。`index@6717288` 区块。
11. token 交换：`POST /api/v1/oauth/token`，body `{provider, code, redirect_uri, state}`，无 Authorization；成功判据 `code===0` + `data.token` + `data.<provider>.access_token`。`index@6711731` / `index@6710263`。
12. 凭据 6 键明文落 localStorage（`oauth:active_provider`、`zcodejwttoken`、`oauth:{zai,bigmodel}:{access_token,user_info}`）；`expires_in` 只进内存、不落盘。`index@6712822`。
13. 本地过期只看 JWT `exp`（30s 余量），过期即 `clearAll()`；无 refresh_token。`src-uVqBd_Eq.js@31023` / `index@6713613`。

### 2.3 terminal token

14. `POST {origin}/api/v1/remote/token`，`Authorization: Bearer <OAuth JWT>`，body `{"role":"terminal"}`，15s 超时，`redirect:'error'`。`index@6762730`。
15. 响应 `{code:0,data:{token:/^[A-Za-z0-9_-]{21}[AQgw]$/, expires_in:正整数}}`；`expires_in` 被 schema 强制但**代码不读**。`src-uVqBd_Eq.js@53993`。
16. 错误分级：401 → `auth-required`（终态）；`>=500` → `relay-unavailable`（可重试）；其它非 2xx / JSON 失败 / schema 不符 → `relay-unavailable`（不可重试，终态）。`index@6762730`。
17. **每次建连都重新换 token，无缓存**：`connect()` 第一步 `await getToken()`。`index@6750856`。
18. 无 JWT 时不发请求，直接 `Promise.reject(new In('auth-required'))`。`index@6768719` 区块。

### 2.4 ws2 与配对

19. `wss://<origin>/api/v1/remote/ws2?token=<22字符>[&mid=<mid>]`；`mid` 仅 `trim()` 后非空才追加；无 subprotocol、无自定义 header、无 Authorization。`validation@125195` / `index@6750856`。
20. 握手超时 15s；升级失败（含 401/403）在客户端**不可见**，一律当可重试网络错误，无限重连、永不终态。`index@6751853` 区块 / `src@55069`（`ho=15e3`）。
21. `auth_init`：`{type, device_sid, meta:{platform?,version?,name?}, client_ts:<Unix 秒>}`，**无 `role`**；`device_sid` ≤128 字节。`src-uVqBd_Eq.js@53028`。
22. `auth_ack` 只在 `state==='authenticating'` 且 `device_sid===本地` 时接受，否则整帧丢弃（不断连、不报错）；接受后置 `authenticated`、清握手定时器、重置重连计数。`index@6754826`。
23. `pair_status` 只有 `waiting`/`matched`（必填）；映射 `matched→paired`，其余→`waiting_terminal`。`src-uVqBd_Eq.js@54488`。
24. 心跳 `{type:'pair_status_query', client_ts}`，5s ±1s（`min(1000, interval*0.2)`），ack 超时 15s；**不带 `device_sid`**。`index@6757252` / `src@55069`。
25. 首次进 waiting 起 30s → `desktop-disconnected` 终态。`index@6757479`。
26. `DEVICE_OFFLINE`：立即重连 + 15s 宽限；到点未 matched → `desktop-disconnected` 终态。`index@6759715`。
27. `KICKED`：60s 窗口内第二次 → `session-conflict`（state=`kicked`）；否则 2–5s 抖动后重连。`src@54932`。
28. `INTERNAL`：已 paired（或 hadPairedSession 且 waiting）→ 保 socket 转 waiting 并重启心跳；否则立即重连。`index@6756579` 区块。
29. `AUTH_FAILED` → 终态 `device-not-owned`；`WRONG_PARAM` → 终态 `invalid-mobile-connection`。`index@6756579`。
30. 退避：网页 500ms×2^n 上限 10s 无抖动；桌面 1s×2^n 上限 30s ±20%。`index@6749561` / `chunk` char@816634。
31. `error.code` 只有五值；未知值整帧被 zod 丢弃。`src@52886` 区块。
32. 关闭码无语义：`close` 回调只写诊断后无条件 `scheduleReconnect()`。`index@6751853`。
33. `data` 帧仅当 `authenticated && state==='paired'` 才转发给上层。`index@6755164` 区块。

### 2.5 数据流

34. `bootstrap-response.result` 只有 6 键：`windowControlSessionId` / `desktopAppVersion?` / `workspaces` / `initialViewState?` / `mobileViewState?` / `mobileTaskViewEpoch?`，**无顶层 `tasks`**。`validation@304850` — `tw=O({windowControlSessionId:Q,desktopAppVersion:Q.optional(),workspaces:YC,initialViewState:QC.optional(),mobileViewState:QC.optional(),mobileTaskViewEpoch:$.optional()})`。
35. `workspace-list-response.result` = `{workspaces, activeWorkspaceKey?, activeTaskId?}`。`validation@305022`。
36. 工作区键 = `workspaceIdentity?.trim() || workspacePath`（网页与桌面多处实现一致；本轮复核网页侧）。`src-uVqBd_Eq.js@52038`。
37. sessions-index 走 `zcode-agent` 通道的 `subscribeSessionsIndexV4({workspacePath,workspaceIdentity?,runtimePolicy:'existing-only'})` → `{ack:{subscriptionId}}`，数据以 `onDynamicSessionsIndexFrame` 事件流推 `snapshot`/`deltas`。`index@2028562`。
38. 条目 schema 16 键，`phase` 是状态主来源；**没有** `pinned`/`archived`/`unreadAt`。`validation@248245`。
39. 任务基底优先 `projectService.readTree({includeDeleted,includeDeletedTasks})` + `controller.readActivities()`；sessions-index 投影 `Dvt` 只在无 controller 时用。`index@2058596`。
40. `pinned`/`archived`/`unreadAt` 来自 `zcodeTaskService.getTaskMeta` / `setTaskUnread` / `readTree`，网页靠 `cvt(entry,{previous})` 从上一版任务对象继承。`index@2026736`。
41. `mobileTaskViewEpoch` 由桌面在进入 `paired` 时新生成，随 bootstrap 下发；手机 `seed` 后回 `mobile-task-view-update{epoch,seq,…}`。`index@6819081` / 桌面 char@403594。
42. 所有业务数据走已认证 relay 的 `{type:'data',payload:{zcode_type,…}}`，**不再附 token**；`rpc-frame` 带 crc32 分片（1 MiB/帧、16 MiB/消息、64 片、30s 组装）。`validation@161461`。

---

## 3. Rust/Kotlin 差异矩阵（按风险排序）

> 只记录，不改码。「3.16.1 行为」列若依据桌面 3.16.3 会显式标注。

| # | 主题 | 当前行为（坐标） | 3.16.1 行为（坐标） | 风险 | 建议动作 |
|---|---|---|---|---|---|
| R1 | `pair_status_query` 多带 `device_sid` | `relay_client.rs@42313`（行 979）、`@45700`（行 1070）：`{type, device_sid, client_ts}` | `src-uVqBd_Eq.js@53117` / 桌面 char@814591：`{type, client_ts}`（strictObject） | **高** | 删掉 `device_sid`。若服务端用同 schema 校验上行，每 10s 一次 `WRONG_PARAM`（服务端行为 UNVERIFIED，但代价不对称：删掉无副作用） |
| R2 | terminal token 只换一次，重连复用 | `EnginePort.kt@11960`（行 278）快路径按 `(url, jwt)` 判等；`resolveTerminalToken@13752` 只在 `ensureSession` 调；`connection_params.rs@21976` 重连复用 `params.auth_token` | `index@6750856`：每次 `connect()` 都 `await getToken()`；桌面 char@379713 同 | **高** | 把 token 供给下沉到「每次 `do_connect` 前回调宿主换 token」。当前长会话 token 过期后，瞬时重连会永久失败（只有终态重建能自愈） |
| R3 | `auth_ack` 不校验 `state` / `device_sid` | `relay_client.rs@30211`（行 683）与 `pair_status_ack` 合并处理；无 `authenticated` 字段 | `index@6754826`：`state!=='authenticating' \|\| device_sid!==本地 → return` | **高** | 增加 `state==Authenticating` + `device_sid` 比对；否则错误终端的 ack 会被当成本端配对成功 |
| R4 | waiting 超时的 reason 错 | `relay_client.rs@39975`（行 920）：`Failure("invalid-mobile-connection", "Desktop did not match…")` | `index@6757479`：`H9('desktop-disconnected', 'Desktop did not match…')` | **中高** | 改成 `desktop-disconnected`。否则 UI 引导「重扫二维码」而正确动作是「等桌面端确认/检查桌面离线」 |
| R5 | `data` 帧无 `paired` 门槛 | `relay_client.rs` 行 692 区块：只判 `payload` 是对象就上抛 | `index@6755164`：`if(!authenticated \|\| state!=='paired') return` | **中** | 加 `state==Paired` 门禁；否则配对前/重连中的帧会进上层 |
| R6 | `INTERNAL` 判终态 | `relay_client.rs@34211`（行 777）：`matches!(code, AUTH_FAILED\|WRONG_PARAM\|INTERNAL)` → `Error` + `intentionally_closed` | `index@6756579` 区块：paired/waiting 保 socket 转 waiting，否则立即重连（**永不终态**） | **中** | `INTERNAL` 改为可恢复（转 waiting 或退避重连）。服务端偶发 `INTERNAL` 会让会话直接死掉 |
| R7 | 关闭码映射 + 未配对不重连 | `relay_client.rs@5230`（行 125）映射 6 个码；`@37607`（行 859）未配对且非 4010 → 直接 `Error` | 网页/桌面：完全不看 code，无条件重连 | **中** | 删除映射与「未配对不重连」分支，统一按可重试网络错误处理。无 3.16.1 证据，且当前行为更脆 |
| R8 | 心跳 10s / ack 30s（+ 探针 → ~40s） | `relay_client.rs@5684`（行 138-140）+ 行 961 探针 | `src@55069`：5s±1s / 15s；超时后 0–2s 抖动立即重连 | **中** | 对齐 5s/15s；去掉「先探针再重连」的两拍（或把探针并入 15s 窗口） |
| R9 | 缺 `sid` 当非法 | `connection_params.rs@20674`（行 463）`get_param(sid)?`；`PairingUrl.kt@3712` `MISSING_SID` | `index@6766124`：缺 sid → `openDevices` | **中** | 协议层至少给出可区分的错误码（`missing-sid` 而非 `bad-input`），让 UI 能提示「该链接需要从设备列表选择」；是否补服务端设备列表属产品决策 |
| R10 | legacy `hash`/`t` + HMAC + `/ws` | `connection_params.rs@20674`（可选 hash/t）、`@21976`（`/ws` 分支）、`relay_client.rs@28250`（`auth_challenge`）、`@25368`（`role:"terminal"`） | 网页硬拒 hash/t；桌面生成链接主动 delete | **中** | 默认 token 模式；legacy 收进显式开关。当前 Android 会接受网页判死的链接，行为分叉 |
| R11 | `pinned`/`archived`/`unreadAt` 恒空 | `SessionsIndexProjection.kt@3428`（行 76）从条目读这三个键 | 条目 schema 无这三键（`validation@248245`）；真实来源是任务服务/`readTree` | **中** | 置顶区恒空、未读永不触发、归档无法隐藏。需要任务元数据通道；短期至少不要把它们当「条目字段」解析（会误导后续实现） |
| R12 | `mobileTaskViewEpoch` / `mobile-task-view-*` 未实现 | `grep mobile-task-view rust/ android/` 0 命中（仅 Kotlin 注释提及） | `index@6819081`：bootstrap 下发 epoch → `seed` → 桌面 `mobile-task-view-request` | **中** | 桌面无法知道手机在看哪个任务 → 已读/未读写入不准。需实现 request/update 回路 |
| R13 | Android `expires_in` 当硬过期门 | `OAuth.kt@13203`（行 333）+ `AuthStore.kt@3544`（行 94） | web：`expires_in` 只进内存；过期只看 JWT `exp` | **中** | 改为 JWT `exp` + 401 兜底；`expires_in` 只做诊断 |
| R14 | `KICKED` 用 message 关键词启发式，且无 60s 窗口 | `relay_client.rs` 行 795 区块：`transient = message.contains(...)`，`Authenticating \|\| transient` 时恢复一次（无时间窗、无 2–5s 抖动） | `src@54932`：60s 窗口 + 2–5s 抖动 | **低中** | 按 60s 窗口实现；关键词启发式应删（服务端 message 文案不受控） |
| R15 | relay 外层帧无 1 MiB 门限 | `relay_client.rs` 无 `MAX_PHYSICAL_FRAME_BYTES`/`envelopeTooLarge` 命中；只有 `rpc_transport.rs:60` 的 base64 分片层 | `index@6754592`：先量 UTF-8 字节再 parse | **低中** | 在 `on_frame` 入口加门限，避免超大帧先被完整 `serde_json` 解析 |
| R16 | 未配对时静默入队 100 条 | `relay_client.rs` 行 1002 区块：`outbound_queue` | 网页 `index@6752776` 区块：直接返回 `unavailable` | **低中** | 行为差异（Rust 更宽容）；但会让「重连期间的请求」延迟到 matched 才发，超时语义与网页不同 |
| R17 | `Rewait` 20s 强制重连 | `relay_client.rs@32626`（行 737）/ `@40671`（行 936） | 网页 `staleWaitingCount` 从不自增、`scheduleStaleWaitingRecovery` 无调用点（死代码） | **低中** | 桌面短暂回 waiting 时会主动断链，可能与桌面配对窗口竞争；建议移除或拉长 |
| R18 | `WorkspaceProjection` 仍读顶层 `tasks` | `ChatViewModel.kt@64147`（行 1544）`val tasks = root?.get("tasks")`；注释引旧基线（`ChatViewModel.kt@3331`，行 82） | bootstrap 无 `tasks`（`validation@304850`） | **低** | 死分支，静默为空；删掉以免误导 |
| R19 | 状态映射把 `hasBackgroundWork` 并入 `running` | `SessionsIndexProjection.kt@2867`（行 64）`hasBackground \|\| phase=="running"` | `index@2026553` 的 `svt`：只看 `phase` | **低** | `hasBackgroundWork` 应作为独立标记（桌面 `Mit()`），否则 completed+后台 会被标成 running |
| R20 | OAuth `app_return_to` 填 redirectUri | `OAuth.kt@5070`（行 143） | web：当前 href（`index@6719738`） | **低** | 语义错位；Android 走 WebView 拦截，暂无功能影响。若将来改系统浏览器回调，必须改 |
| R21 | 无版本自愈 | Rust/Kotlin 0 命中 `desktopAppVersion` | `index@6771515`：`/remote/v4` 上比对并整页 replace | **低** | 客户端与服务端版本漂移时不会自愈；建议至少记录/提示 |
| R22 | `meta.name = "zemote"` | `device_info.rs@1073` | web `mobile-browser` | **低** | 仅展示用途；无服务端读取证据（UNVERIFIED） |
| R23 | 无服务端设备列表 | `grep api/v1/remote/device android/ rust/` 0 命中（本地 `DeviceRepository` 是另一回事） | `index@6766124` → `GET /api/v1/remote/device` + 自动选中 | **低（功能）** | 只在「链接无 sid」时才有差距；有 sid 的常规路径不受影响 |
| R24 | `meta.version` 缺省 `"web"` | `relay_client.rs@15585` | 同（`appVersion ?? "web"`） | 无 | 保持 |

---

## 4. 旧基线残留清单（3.15.3 / 旧 Dart 基线，无 3.16.1 对应物）

| # | 残留 | 本仓坐标 | 权威源实际行为 |
|---|---|---|---|
| O1 | WebSocket 关闭码 4004/4009/4010/4011/4012/4013 映射 | `relay_client.rs@5230`（行 125-135） | 网页/桌面完全不看 code（§1-9a） |
| O2 | 关闭码决定「未配对是否重连」 | `relay_client.rs@37607`（行 859） | 网页/桌面无条件 `scheduleReconnect()` |
| O3 | legacy `/ws` + `auth_challenge`/`auth_response` + HMAC proof | `connection_params.rs@21976`（行 493-497）、`relay_client.rs@28250`（行 648-682） | 3.16.1 只有 `/api/v1/remote/ws2` |
| O4 | `auth_init` legacy 分支发 `role:"terminal"` + 毫秒 `client_ts` | `relay_client.rs@25368`（行 581） | token 模式无 `role`、`client_ts` 为秒（`src@53028` / `index@6751388`） |
| O5 | `parse()` 接受 `hash`/`t`，且 `t` 做整数解析 | `connection_params.rs@20674`（行 463-468） | 网页见任一即返回 `null`（`src@51830`） |
| O6 | 心跳 10s / ack 30s / waiting 隔拍 20s | `relay_client.rs@5684`（行 138-140）、行 958 | 5s±1s / 15s |
| O7 | `Rewait` 20s 定时器 | `relay_client.rs`（行 737、936） | 网页该机制是死代码，桌面没有 |
| O8 | `DEAD_LINK_THRESHOLD_MS=25s` + `poke()` | `relay_client.rs`（行 149、1052-1091） | 网页用 `suspend()`/`recoverConnection()`，无 poke 概念 |
| O9 | 出站队列 100 条 + matched 后冲刷 | `relay_client.rs`（行 151、1002-1045） | 网页/桌面未配对直接返回 `unavailable` |
| O10 | `KICKED` message 关键词启发式 + `kick_recovery_attempted` | `relay_client.rs`（行 795-819） | 60s 时间窗 + 2–5s 抖动 |
| O11 | failure reason 命名 `auth-failed` / `wrong-param` / `relay-internal` / `kicked` | `relay_client.rs`（行 781-814） | 网页 `device-not-owned` / `invalid-mobile-connection` / `session-conflict` |
| O12 | 退避 1s→2→4→8→15s | `relay_client.rs`（行 1100-1102） | 网页 500ms→10s 无抖动；桌面 1s→30s ±20% |
| O13 | waiting 超时 reason = `invalid-mobile-connection` | `relay_client.rs@39975`（行 920） | 网页 = `desktop-disconnected` |
| O14 | 无 `authenticated` 布尔量 | `relay_client.rs` 全仓 `authenticated` 仅注释/测试 | 网页/桌面都有独立标志，`data`/`pair_status_ack` 依赖它 |
| O15 | 注释把 `config.token` 说成「OAuth JWT」 | `connection_params.rs@19237`（行 437）— `OAuth JWT（`zcodejwttoken`），由宿主登录后注入…relay 走 wss://<host>/api/v1/remote/ws2?token=<jwt>&mid=…`；`session.rs@22095`（行 469）— `// 3.16.1：OAuth JWT 由宿主登录后通过 config.token 注入，不来自 URL。`（紧接其下 `params.auth_token = Some(token);` 在行 472）；`EnginePort.kt` 行 281 注释「OAuth JWT 不能直接连 ws2」但紧接着 `resolveTerminalToken` 才换成 terminal token | ws2 的 `?token=` 是 **22 字符 terminal token**，不是 OAuth JWT（`src@53993` 正则；`index@6762730` 换 token）。这正是 `old-baseline-revocations.md` 第 7 条明令禁止的错误实现，注释层面仍残留 |
| O16 | bootstrap 顶层 `tasks` 解析 + 引用旧基线文档 | `ChatViewModel.kt@64147`（行 1544）；`ChatViewModel.kt@3331`（行 82）— `字段名取自对端 tasks[i] 的权威 schema（16 键，见 archive/pre-3.16.1-auth/docs/desktop/11-host进程与会话任务.md）` | 3.16.1 bootstrap 无 `tasks`（`validation@304850`）。**该注释把已归档的 3.15.3 文档当作「权威 schema」** |
| O17 | `SessionsIndexProjection` 从条目读 `pinned/archived/unreadAt` | `SessionsIndexProjection.kt@3428`（行 76-78） | 条目 schema 无这三键（`validation@248245`） |
| O18 | `SessionsIndexProjection` 类注释称任务「由 sessions-index 提供」 | `SessionsIndexProjection.kt` 文首 — `任务由每个工作区的 subscribeSessionsIndexV4 提供` | 只是无 controller 时的备选；有 controller 时基底是 `readTree`（§1-5） |

---

## 5. 网页/桌面证据混淆清单

| # | 位置 | 问题 | 处置 |
|---|---|---|---|
| X1 | 01 文首「桌面端 `app.asar`（3.16.1 同发布）」；02 文首同 | 版本标错 | 改为「桌面 3.16.3」；凡 asar 结论不得写成 3.16.1 |
| X2 | 01 §7.5 表头「桌面端 asar（只读，确认主机行为）」 | 未标版本 | 补「3.16.3」；该表的 `qpe`/`Vd` 结论本身成立（本轮复核 char@813558 / char@474879） |
| X3 | 04 §8-E2「桌面端 bootstrap / workspace-list 生成」 | 用桌面 `buildBootstrapResult` 描述 `result` 形状，报告标题却是「ZCode Web 3.16.1」 | 形状有网页 schema 独立佐证（`validation@304850`），结论可用；但「生成方」必须标桌面，不能写成网页生成 |
| X4 | 04 §2 时序图把 relay 画成 `wss://<origin>/ws/remote/v4` | 该路径在网页与桌面都不存在（0 命中）；且坐标 6633951 是测试夹具 | 已纠错（§1-1c） |
| X5 | 03 §4 开头「网页与桌面逐字相同」 | 前提成立（schema 文本逐字相同），但不能推出「契约相同」——只说明 3.16.3 与 3.16.1 之间协议形状未变 | 结论可用；所有「3.16.1 桌面」表述改「3.16.3 桌面」 |
| X6 | 03 §7-D6 引「桌面 `main/index.js@380091` 同语义」 | 380091 是 `auth_init` 发送处；真正的 ack 校验在 char@381403 | 坐标修正（B 路 X5 独立发现同一处） |
| X7 | 01 §6.3「`auth_init` 的 `meta` … Rust 侧同名回落一致」 | 把「字段名一致」当「值一致」；`name` 恒不同 | 已纠错（§1-3b） |
| X8 | 04 §7.2-D14「Rust `open_bridge` 只带 `workspaceKey/taskId`」 | 事实错误：`open_bridge_request` **带 `bridgeGeneration`**，重开时还带 `recoveryId` | 见下方补漏 |
| X9 | 02 §5.2 桌面凭据表（`credentials.json`、`refresh_token`、CLI 轮询流） | 全部是桌面 3.16.3 行为，被放在「ZCode Web 3.16.1」报告里对照 | 已标注为「对照」；实现时不得把 `refresh_token` / CLI 轮询当成网页契约 |
| X10 | 03 §5.2 把「网页/桌面/Rust」三列并排放在「关闭码」表 | 容易被读成三端都在解析 code | 拆表或明确标注「Rust 列是旧基线」 |

**补漏（X8 的正确坐标）**：`rust/crates/protocol/src/zemote_client.rs@99606`（行 2437 `open_bridge_request`）— `payload.insert("bridgeGeneration".into(), json!(bridge_generation));`；重开版（行 2459 `reopen_bridge_request`）额外 `if let Some(recovery_id) = recovery_id { payload.insert("recoveryId".into(), recovery_id.clone()); }`。即 04 的 D14/D15 应裁决为 **CONFIRMED 已实现**，不是 UNVERIFIED 缺口。

---

## 6. 未解问题（客户端源码无法闭合）

| # | 问题 | 现状 | 为什么源码不能裁决 |
|---|---|---|---|
| U1 | 服务端是否用同一套 `strictObject` 校验**上行**帧（决定 R1 是否必然 `WRONG_PARAM`） | **UNVERIFIED** | 客户端 schema 只约束入站；服务端不在快照/asar 内 |
| U2 | ws2 升级阶段是否返回 401，条件是什么（token 过期/一次性/mid 绑定/Origin） | **UNVERIFIED** | 网页用浏览器 WebSocket、桌面用 `ws` 且无 `unexpected-response` 监听 → 结构性不可见 |
| U3 | terminal token 是否一次性、`expires_in` 真实数值 | **UNVERIFIED** | 三端都不读 `expires_in`；「一次性」与「过期」在客户端不可区分 |
| U4 | `auth_init.meta.name/platform/version` 是否参与服务端配对或版本门控 | **UNVERIFIED** | 服务端逻辑不可见；桌面 `forceUpdate.minimalVersion` 是自身更新检查，与 relay meta 无代码关联 |
| U5 | 手机 Web 远端里 `windowControllerService` 是否可用（决定任务基底是 `readTree` 还是 sessions-index） | **UNVERIFIED** | 客户端**会**装配该服务（`index` 11 处命中），但远端 host 是否实现 `list`/`readActivities` 需真机 |
| U6 | 服务端按 `app_version` 渲染版本 HTML 的回落策略（缺失/非法/不存在的版本） | **UNVERIFIED** | 只有 3.16.1 一个版本的快照，无法做对照 |
| U7 | `terminal_sid` 的用途 | **UNVERIFIED** | 三端 schema 都有、代码都不读 |
| U8 | `data.user` 的真实字段形态（驼峰 vs 下划线；`avatar` 是 URL 还是裸 base64） | **UNVERIFIED** | 需抓一次真实 `/api/v1/oauth/token` 响应 |
| U9 | 服务端是否接受 `redirect_uri=https://zcode.z.ai/remote/v4`（Android 现状） | **UNVERIFIED** | 白名单在服务端；网页自己就在用该值（强旁证），但「是否精确匹配含 query」未知 |
| U10 | 3.16.1 桌面 `app.asar` 与 3.16.3 的逐字节差异 | **UNVERIFIED** | 本机只有 3.16.3 |

**与 B 路的差异（不影响裁决）**：B 路 `02-token-relay.md` §2.1 指出 03 的「Android 没有任何刷新路径」不成立（存在终态重建路径 `ChatViewModel.kt` 行 798-809 → `ensureSession` 重新换 token）。本轮独立复核认可该修正：准确表述是「**瞬时重连**复用旧 token，只有**终态重建**才会刷新」。§3-R2 已按此措辞。

---

## 附：本轮读取与清理

- 网页：Node `Buffer.indexOf` / `Buffer.byteLength` 定位与取片段；未执行任何快照 JS。
- 桌面：`node tools/asar.mjs cat/grep` 只读；未执行 asar 内代码。
- 本仓：`grep -n` + 字节偏移计算；未运行 `cargo test`、未改任何文件。
- 临时脚本只放在系统临时目录 `/tmp/xc/`，不在仓库内；仓库内仅新增本文件。
