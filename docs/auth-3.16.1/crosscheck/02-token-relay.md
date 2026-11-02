# 02 · 交叉验证（B 路）：terminal token / ws2 认证 / 配对与重连

> 目标：独立重读源码，逐条裁决 `docs/auth-3.16.1/round1/03-terminal-token-relay.md`（下称 **03**）的结论。
> 只读；未修改任何源码、未改动 Round 1 报告。
>
> **裁决对象与版本漂移**：本轮裁决的是 **03 的 15:52 版（51,636 B）**。收尾复核时发现该文件已于 **16:05**
> 被另一轮修改（现 **58,904 B**，SHA-256 `cb9f87b5…70e849`），新增了 §6.1、D15、D16、§10「二次复核记录」。
> 已逐条比对：
> - 本报告 3 条 **CONTRADICTED** 所引原文在修订版中**仍然存在且未改**（`§4.2` 注、`D2` 影响句、`D7` 表 KICKED 行），裁决不变；
> - 本报告的 **M3 / M6 / M8** 三条 MISSING 已被修订版以 **D15 / D16 / §10-C3** 独立覆盖（结论一致，本轮独立复现确认）；
> - 修订版仍未修复 `D6` 的桌面坐标（仍是 `380091`，应为 `381367`）与 `§5.2` 的 `noteWebRemoteRelayConnectAttempt` 符号名（修订版 §10-C1 已自行标 UNVERIFIED）。
>
> 坐标格式：`<文件>@<UTF-8 字节偏移>`；Rust/Kotlin 源码额外在括号内附 `L<行号>` 便于阅读。所有偏移均以本机文件实测字节数为准，
> 且已用 `Buffer.indexOf` 复核（**注意：JS 字符串 `indexOf` 是字符下标，与字节偏移不同，
> 主包含中文，字符下标比字节偏移小约 12 万——复核必须用 Buffer**）。
>
> 三类证据严格区分：
> - **【网页 3.16.1】** `Reference_web/3.16.1/assets/`（SHA-256 已复核，与 `source-index.md` 一致）
> - **【桌面 3.16.3】** `C:\Users\wdn32\AppData\Local\Programs\ZCode\resources\app.asar`（只读 dump 到临时目录读字节，读完删除）
> - **【本仓】** `rust/` + `android/`
>
> 桌面端版本已实测：`package.json@line58 "version": "3.16.3"`、
> `out/main/chunk-CJROBAMZ.js@101 var Jr="3.16.3",oI="a82dd43e",rI="2026-10-23T02:41:37.939Z"`。
> **桌面证据一律是 3.16.3，不是 3.16.1**——03 的开篇免责声明成立，但正文有多处把两者当同一契约使用（见 §5）。

---

## 0. 本轮新增的可复现事实（先列，后面引用）

### 0.1 zod 别名解析（03 未给出定义坐标）

网页端 `src-uVqBd_Eq.js` 的 schema 构造器全部是**别名**，必须先解析到 `validation-BBf1FA1a.js` 的真实定义：

| 网页别名 | validation 导出名 | 真实局部符号 | 语义 |
|---|---|---|---|
| `_`（strictObject） | `vi` | `Tc` | `function Tc(e,t){return new wc({type:\`object\`,shape:e,catchall:bc(),...m(t)})}` |
| `p`（object） | `mi` | `O` | `function O(e,t){return new wc({type:\`object\`,shape:e??{},...m(t)})}` |
| `u`（discriminatedUnion） | `ai` | `A` | `new Oc({type:\`union\`,options:t,discriminator:e,...})` |
| `d`（union） | `bi` | `k` | `new Dc({type:\`union\`,options:e,...})` |
| `n`（enum） | `$r` | `M` | — |
| `g`（literal） | `ui` | `N` | — |

**strictObject 的真实语义（网页）**：
`validation-BBf1FA1a.js@92579` —
`function O(e,t){return new wc({type:\`object\`,shape:e??{},...m(t)})}function Tc(e,t){return new wc({type:\`object\`,shape:e,catchall:bc(),...m(t)})}`
`bc` = ZodNever：`validation-BBf1FA1a.js@90889` — `function E(){return xa(vc)}`；`validation-BBf1FA1a.js@91017` — `function bc(e){return Sa(yc,e)}`（`yc=g(\`ZodNever\`)`）。
多余的键会走到 catchall=never 分支并 push issue：`validation-BBf1FA1a.js@33517` —
`return s.length&&n.issues.push({code:\`unrecognized_keys\`,keys:s,input:t,inst:a,continue:!0}),...`
→ **safeParse 失败**。`continue:!0` 只表示"不中断后续字段校验"，不代表"忽略"。

**strictObject 的真实语义（桌面 3.16.3）**：同一份 zod v4 核心，只是符号名不同。
`chunk-CJROBAMZ.js@444171` —
`function pS(t,n){return new kr({type:"object",shape:t,catchall:Ha(),...I.normalizeParams(n)})}c(pS,"strictObject")`
`chunk-CJROBAMZ.js@442157` — `function Ha(t){return kp(qm,t)}c(Ha,"never")`
`chunk-CJROBAMZ.js@61432` — `return l.length&&r.issues.push({code:"unrecognized_keys",keys:l,input:n,inst:i,continue:!0}),...`

→ **结论：两端的 strictObject 确实拒绝多余键（客户端侧，解析入站帧时）。**
但这**只能证明客户端校验入站帧**；服务端是否用同一套 schema 校验**上行帧**，源码里没有证据（见 §7）。

### 0.2 关键别名（用于读懂引用代码）

| 别名 | 来源 | 含义 |
|---|---|---|
| `An`（网页） | src 导出 `C` = `po` | RetryPolicy 类，默认 `{initialMs:1e3,maxMs:3e4,jitter:.2}` |
| `In`（网页） | src 导出 `E` = `uo` | `WebRemoteControlV2ConnectionError`（reason, retryable） |
| `Nn`（网页） | src 导出 `D` = `lo` | HTTP 响应校验 `co[type].safeParse` |
| `Kr`（网页） | src 导出 `_` = `xo` | Heartbeat 类（默认 5s/1s/15s） |
| `cee`（网页） | src 导出 `w` = `fo` | pair_status → 内部状态映射 |
| `mee`（网页） | src 导出 `y` = `ho` = `15e3` | 握手超时 |
| `uee`（网页） | src 导出 `x` = `vo` | 心跳抖动计算 |
| `Qt`（网页） | validation 导出 `tn` = `_m` | `{maxPhysicalFrameBytes:Fp.maxFrameBytes,...}`，`Fp.maxFrameBytes=1024*1024`（`validation@148182`） |
| `ah`（桌面） | chunk 导出 `Ud` = `jC` | RetryPolicy（1e3/3e4/.2） |
| `ch`（桌面） | chunk 导出 `Vd` = `qh` | Heartbeat（5e3/1e3/15e3） |
| `ih`（桌面） | chunk 导出 `Td` = `cme` | pair_status 映射 |
| `Fe`（桌面） | chunk 导出 `Sd` = `$C` | ConnectionError |
| `qa`（桌面） | `import{WebSocket as qa}from"ws"`（`index.js@377962`） | ws 客户端 |

---

## 1. 裁决表

> 裁决枚举：CONFIRMED / CONTRADICTED / MISSING / UNVERIFIED。
> 一条 03 结论若含多个可独立证伪的子断言，拆行裁决。

| # | 03 的结论（摘要） | 裁决 | 坐标 + 原文（≤300 字符） |
|---|---|---|---|
| **1a** | ws2 的 `?token=` 不是 OAuth JWT，是 `/api/v1/remote/token` 换来的 22 字符短 token | **CONFIRMED** | 形状本身即可证伪 JWT（无 `.` 分段）：`WEB:src-uVqBd_Eq.js@53993` — `oo=p({token:y().regex(/^[A-Za-z0-9_-]{21}[AQgw]$/),expires_in:m().int().positive()})`；`DESKTOP:chunk-CJROBAMZ.js@815896` — `_B=e.object({token:e.string().regex(/^[A-Za-z0-9_-]{21}[AQgw]$/),expires_in:e.number().int().positive()})` |
| **1b** | 请求 `POST /api/v1/remote/token`，body `{"role":"terminal"}`，响应 `data.token` | **CONFIRMED** | `WEB:index-DbEHT6Gg.js@6762730` — `t(new URL(Er.token.path,e),{method:\`POST\`,headers:{Authorization:\`Bearer ${n}\`,"Content-Type":\`application/json\`},body:JSON.stringify({role:\`terminal\`}),signal:AbortSignal.any([r,AbortSignal.timeout(15e3)]),redirect:\`error\`})`；请求体 schema 也是 strict：`WEB:src-uVqBd_Eq.js@53858` — `_({device_mid:U}),_({}),_({device_sid:U}),_({role:$a});` |
| **1c** | 该 token 是**一次性**的 | **UNVERIFIED** | 客户端源码无任何"消费/单次"痕迹。03 自己在 §6 #4 也标 SPECULATION，与 §1.1 的断言自相矛盾。仅能确认：网页/桌面每次建连都换新 token（见 2a/2b），因此"一次性"与"过期"在客户端**不可区分** |
| **2a** | 网页每次 `connect()` 都重新换 token、无缓存 | **CONFIRMED** | `WEB:index-DbEHT6Gg.js@6750704` — `let n=await this.options.getToken(t.signal);if(t.signal.aborted||e!==this.socketGeneration)return;let r=new URL(this.options.relayWsUrl);r.searchParams.set(\`token\`,n)`。`getToken` 的注入点每次都现取 JWT 再打接口：`WEB:index-DbEHT6Gg.js@6768719` — `getToken:e=>{let t=this.options.auth.getZCodeJwtToken();return t?this.options.token(t,e):Promise.reject(new In(\`auth-required\`))}` |
| **2b** | 桌面每次 `connect()` 都重新换 token、无缓存 | **CONFIRMED** | `DESKTOP:index.js@379713` — `o=await this.options.getToken(t)`；`DESKTOP:index.js@407338` — `getToken:s(async ae=>{let ut=await Gn(),jo=await zn.token(ut.jwt,AbortSignal.any([ne,ae]));return await Gn(),...jo},"getToken")`（每次现取 jwt、现打接口，无 memo） |
| **2c** | Android 只在 `ensureSession()` 换一次；Rust 重连复用 | **CONFIRMED** | `EnginePort.kt@11948（L278）` — `if (engine != null && _connectedUrl.value == target && connectedAuthToken == token) return`；`EnginePort.kt@12327（L284）` — `val terminalToken = resolveTerminalToken(target, token)`；`connection_params.rs@22405（L509）` — `if let Some(token) = &self.auth_token { parameters.push(("token", token.as_str())); }`，`relay_client.rs@23837（L540）` — `let uri = self.params.relay_ws_uri();`（重连走同一 `params`） |
| **3** | `expires_in` 三家都只校验不读，无主动刷新 | **CONFIRMED** | 网页取 token 时不读该字段：`WEB:index-DbEHT6Gg.js@6763285` — `return s.data.token`（`s` 已过 schema，`expires_in` 只被校验）；桌面 `DESKTOP:index.js@377837` — `return i.data.token`；Kotlin 只存不读：`OAuth.kt@16907（L421）` — `RemoteToken(token = token, expiresInSecs = expiresIn)`，全仓 `grep expiresInSecs` 仅命中 `OAuth.kt@4017（L111）`（data class 声明）与 `OAuth.kt@16907（L421）`（构造） |
| **4a** | `auth_init` 无 `role`，字段 `type/device_sid/meta?/client_ts?` | **CONFIRMED** | `WEB:src-uVqBd_Eq.js@53028` — `_({type:g(\`auth_init\`),device_sid:Za,meta:Qa.optional(),client_ts:H.optional()})`；`DESKTOP:chunk-CJROBAMZ.js@814485` — `fB=e.strictObject({type:e.literal("auth_init"),device_sid:Vh,meta:MC.optional(),client_ts:Wt.optional()})`。无 `role` 键 |
| **4b** | `client_ts` 是 Unix **秒** | **CONFIRMED** | `WEB:index-DbEHT6Gg.js@6751388` — `this.send({type:\`auth_init\`,device_sid:this.options.deviceSid,meta:{platform:\`web\`,version:this.options.appVersion??\`web\`,name:\`mobile-browser\`},client_ts:Math.floor(Date.now()/1e3)})`；桌面同：`DESKTOP:index.js@380091` |
| **4c** | 网页 `meta` 恰为 `{platform:"web",version:appVersion??"web",name:"mobile-browser"}` | **CONFIRMED** | `WEB:index-DbEHT6Gg.js@6751450` — `meta:{platform:\`web\`,version:this.options.appVersion??\`web\`,name:\`mobile-browser\`}`。且 `meta` 本身是 strictObject（`Qa=_({...})`，`WEB:src-uVqBd_Eq.js@52811`），**多一个键即失败** |
| **5a** | `pair_status_query` 是 strictObject，只允许 `{type, client_ts}` | **CONFIRMED** | `WEB:src-uVqBd_Eq.js@53117` — `_({type:g(\`pair_status_query\`),client_ts:H.optional()})`；`DESKTOP:chunk-CJROBAMZ.js@814591` — `hB=e.strictObject({type:e.literal("pair_status_query"),client_ts:Wt.optional()})` |
| **5b** | 服务端会因 `device_sid` 判 Rust 非法 | **UNVERIFIED** | 客户端 schema 只能证明"客户端解析入站帧时拒绝多余键"。**服务端校验上行帧的行为在源码中不可见**。03 用"大概率"表述是对的，但结论句写成了确定事实 |
| **6a** | `auth_ack` 必须 `state==="authenticating"` 且 `device_sid===本地`，否则整帧丢弃 | **CONFIRMED** | `WEB:index-DbEHT6Gg.js@6754841` — `case\`auth_ack\`:if(this.state!==\`authenticating\`||n.device_sid!==this.options.deviceSid)return;this.authenticated=!0,clearTimeout(this.handshakeTimer),this.retry.acknowledged(),this.applyPairStatus(n.pair_status);break;`；`DESKTOP:index.js@381367` — `if(this.state!=="authenticating"||t.device_sid!==this.deviceSid)return;` |
| **6b** | Rust 把 `auth_ack`/`pair_status_ack` 合并处理且不校验 | **CONFIRMED** | `relay_client.rs@30199（L683）` — `Some("auth_ack") \| Some("pair_status_ack") => {` … `self.apply_pair_status(status.as_deref(), transport, now_ms, out);`。Rust **没有** `authenticated` 字段（`grep authenticated` 仅命中注释与测试），因此连"必须先认证"这层门槛也不存在 |
| **7** | `pair_status` 只有 `waiting`/`matched` | **CONFIRMED** | `WEB:src-uVqBd_Eq.js@52914` — `eo=n([\`waiting\`,\`matched\`])`；`DESKTOP:chunk-CJROBAMZ.js@814237` — `DC=e.enum(["waiting","matched"])`。两处都是**必填**（非 `.optional()`） |
| **8a** | 网页/桌面完全不解析 WebSocket 关闭码 | **CONFIRMED** | 网页 close 监听器只写诊断，两条分支体完全相同：`WEB:index-DbEHT6Gg.js@6752174` — `if(this.hadPairedSession){this.scheduleReconnect();return}this.scheduleReconnect()`；桌面 close 监听器无 `code` 形参：`DESKTOP:index.js@380623` — `a.on("close",()=>{a===this.socket&&(this.state!=="paired"&&Al(Date.now()-this.connectStartedAt,!1,{attempt:this.connectAttempt}),this.scheduleReconnect())})` |
| **8b** | Rust 的 4004/4009/4010/4011/4012/4013 映射在 3.16.1 无证据 | **CONFIRMED** | `DESKTOP:index.js` 全文 `4013` 命中 **0** 次、`unexpected-response` 命中 **0** 次（Buffer 实测）；`relay_client.rs@5230（L125）` — `4004 => Some("session-not-found"),4009 => Some("session-conflict"),4010 => Some("desktop-disconnected"),4011 => Some("session-expired"),4012 => Some("workspace-closed"),4013 => Some("invalid-mobile-connection")` |
| **9** | 网页 3.16.1 见 `hash`/`t` 任一即拒绝；Rust 仍保留 legacy HMAC | **CONFIRMED** | `WEB:src-uVqBd_Eq.js@51830` — `function Ja(e){if(e.has(\`hash\`)\|\|e.has(\`t\`))return null;let t={};for(let[n,r]of[[\`deviceSid\`,\`sid\`],[\`deviceMid\`,\`mid\`],[\`deviceName\`,\`name\`],[\`appVersion\`,\`app_version\`]]){...}`；调用点 `WEB:index-DbEHT6Gg.js@6766002` — `let t=mr(e.searchParams);if(!t){this.options.onView({kind:\`failure\`,failure:{reason:\`invalid-mobile-connection\`}});return}`；Rust：`connection_params.rs@20716（L464）` — `let pass_hash = get_param(&uri, "hash");`（Optional，不拒绝）+ `relay_client.rs@28238（L648）` 的 `auth_challenge` + HMAC proof 分支 |
| **10a** | 网页心跳 5s±1s / ack 超时 15s | **CONFIRMED** | `WEB:src-uVqBd_Eq.js@55067` — `G=5e3,mo=1e3,ho=15e3,go=2e3`；`WEB:src-uVqBd_Eq.js@55634` — `acknowledged(){...this.watchdog=setTimeout(this.expired,this.timing.ackTimeoutMs??15e3),...}` + `this.schedule()}schedule(){this.heartbeat=setTimeout(()=>{...this.query(),this.schedule()},yo(this.timing.intervalMs??5e3,this.timing.jitterMs??(this.timing.intervalMs===void 0?1e3:void 0)))}`；`vo` 的抖动上限 = `min(1e3, floor(n*0.2))` → 5s 时正好 ±1s。运输层构造处**未传任何覆盖值**（全文 `heartbeatIntervalMs:`/`heartbeatAckTimeoutMs:` 命中 0 次） |
| **10b** | 桌面心跳同一默认（5s / 15s / 1s 抖动） | **CONFIRMED** | `DESKTOP:chunk-CJROBAMZ.js@817860` — `var qh=class{constructor(n,r,a={}){...}acknowledged(){...setTimeout(this.expired,this.timing.ackTimeoutMs??15e3),...}schedule(){...UC(this.timing.intervalMs??5e3,...)}`；构造处只传两个参数：`DESKTOP:index.js@378440` — `heartbeat=new ch(()=>this.send({type:"pair_status_query",client_ts:Math.floor(Date.now()/1e3)}),()=>this.scheduleReconnect());` |
| **10c** | Rust 10s/30s（且 waiting 态隔拍） | **CONFIRMED** | `relay_client.rs@5674（L138）` — `pub const HEARTBEAT_INTERVAL_MS: i64 = 10_000;`、`relay_client.rs@5794（L140）` — `HEARTBEAT_ACK_TIMEOUT_MS: i64 = 30_000;`、`relay_client.rs@41492（L958）` — `if self.state == RelayState::Waiting && self.heartbeat_tick % 2 == 1 { return; }` |
| **11a** | `AUTH_FAILED`：网页终态 `device-not-owned`；桌面首次强制重注册+立即重连；Rust 终态 `auth-failed` | **CONFIRMED** | 网页 `WEB:index-DbEHT6Gg.js@6756293` — `if(e===\`AUTH_FAILED\`\|\|e===\`WRONG_PARAM\`){this.enterTerminalFailure(H9(e===\`AUTH_FAILED\`?\`device-not-owned\`:\`invalid-mobile-connection\`));return}`；桌面 `DESKTOP:index.js@381043` — `t.code==="AUTH_FAILED"&&this.state==="authenticating"&&!this.registrationRetried?(this.registrationRetried=!0,this.registrationPending=!0,this.scheduleReconnect(0))`；Rust `relay_client.rs@34327（L781）` — `Some("AUTH_FAILED") => "auth-failed"` |
| **11b** | `WRONG_PARAM`：网页 `invalid-mobile-connection`；桌面无专分支落 `relay-unavailable` 终态；Rust `wrong-param` | **CONFIRMED** | 同上网页坐标；桌面 `DESKTOP:index.js@381200` — `t.code==="INTERNAL"\|\|t.code==="DEVICE_OFFLINE"?this.scheduleReconnect():this.handleConnectionError(new Fe("relay-unavailable"))`（`WRONG_PARAM` 落入最后一支）；Rust `relay_client.rs@34419（L783）` |
| **11c** | `INTERNAL`：网页 paired/waiting 保 socket；桌面退避重连；Rust **终态** | **CONFIRMED** | 网页 `WEB:index-DbEHT6Gg.js@6756677` — `if(e===\`INTERNAL\`){if(this.state===\`paired\`\|\|this.hadPairedSession&&this.state===\`waiting\`){this.enterWaitingForDeviceAfterRelayError(t\|\|e);return}this.recoverFromRelayInternal(t\|\|e);return}`；桌面见 11b 坐标（`INTERNAL` → `scheduleReconnect()`）；Rust `relay_client.rs@34203（L777）` — `if matches!(code, Some("AUTH_FAILED") \| Some("WRONG_PARAM") \| Some("INTERNAL"))` → `set_state(Error)` + `intentionally_closed = true` |
| **11d** | `DEVICE_OFFLINE`：网页 15s 宽限→终态；桌面退避重连；Rust 只上报、保留重连 | **CONFIRMED** | 网页 `WEB:index-DbEHT6Gg.js@6759780` — `this.desktopOfflineFailureTimer\|\|=setTimeout(()=>{...this.enterTerminalFailure(H9(\`desktop-disconnected\`,e))},this.options.desktopOfflineGraceMs??15e3)`；Rust `relay_client.rs@36234（L824）` — `let reason = code.unwrap_or("relay-error").to_lowercase();out.push(RelayEvent::Failure(RelayFailure::new(&reason, message)));`（不终态、不关 socket） |
| **11e** | `KICKED`：网页/桌面 60s 窗口内第二次才终态；Rust 用 message 关键词启发式 | **CONFIRMED**（Rust 侧条件需补，见 §2.3） | 网页 `WEB:src-uVqBd_Eq.js@54932` — `kickedDelay(e=Date.now()){return this.lastKickAt!==void 0&&e-this.lastKickAt<6e4?null:(this.lastKickAt=e,2e3+Math.floor(this.random()*3001))}`；桌面 `DESKTOP:chunk-CJROBAMZ.js@816736` 同语义；Rust `relay_client.rs@34858（L795）` |
| **12** | ws2 升级 401 无法从客户端源码证实；设备离线/账号不匹配走 error 帧 | **CONFIRMED** | 网页用浏览器 WebSocket、单参构造：`WEB:index-DbEHT6Gg.js@6750958` — `let i=new this.WebSocketCtor(r.toString());`；桌面 `qa` = `ws`，全文无 `unexpected-response`；协议内确有 `DEVICE_OFFLINE`/`AUTH_FAILED` 两个 error 帧值（`WEB:src-uVqBd_Eq.js@52949` — `to=n([\`AUTH_FAILED\`,\`WRONG_PARAM\`,\`KICKED\`,\`INTERNAL\`,\`DEVICE_OFFLINE\`])`） |
| **13** | D2 影响句："（Android）**没有任何刷新路径**" | **CONTRADICTED** | 存在一条重建路径：`ChatViewModel.kt@32069（L798-809）` — `if (relay != RelayState.Error && relay != RelayState.Kicked && relay != RelayState.Closed) { return true }; engine.disconnect()` 之后再次 `engine.ensureSession(url, token)`，而 `EnginePort.kt@17262（L408）` 的 `disposeLocked()` 会 `_connectedUrl.value = null`，使快路径失效 → **重新换 token**。准确表述见 §2.1 |
| **14** | §4.2 注："`pair_status` 缺失或非字符串 → 两个分支都不命中，只更新 `lastPairStatusAckAt`" | **CONTRADICTED** | 这是 **Rust** 的行为，不是网页/桌面的。网页 `cee(e)=fo(e)=e==='matched'?'paired':'waiting_terminal'`，任何非 `matched` 值都命中 waiting 分支：`WEB:index-DbEHT6Gg.js@6755327` — `applyPairStatus(e){if(this.lastPairStatusAckAt=Date.now(),...),cee(e)===\`waiting_terminal\`){...}`；桌面 `this.setState(ih(t.pair_status))` 同语义（`DESKTOP:chunk-CJROBAMZ.js@816634`）。实际不可达的原因不是"两分支都不命中"，而是 zod 必填校验把整帧丢了。详见 §2.2 |
| **15** | D7 表 Rust `KICKED` 行："非 transient 直接 kicked" | **CONTRADICTED** | 遗漏了 `state === Authenticating` 这个或条件：`relay_client.rs@35189（L801）` — `if !self.kick_recovery_attempted && (self.state == RelayState::Authenticating \|\| transient) {` → 在 authenticating 态**即使 message 无关键词**也会先恢复一次（发 `Failure(session-conflict)` 后 `reconnect`）。详见 §2.3 |

**统计**：裁决表 30 行 = CONFIRMED 25 行 / CONTRADICTED 3 行 / UNVERIFIED 2 行；另有 MISSING 12 项（§3），其中 3 项（M3/M6/M8）已被 03 修订版独立覆盖、本轮复现确认，**真正属于修订版仍未覆盖的为 9 项**。

---

## 2. CONTRADICTED 纠错

### 2.1 Android 并非"没有任何刷新路径"，但自动重连确实复用旧 token

**03 原文**（D2 影响）："长会话内 token 过期 → 每次重连都失败，且没有任何刷新路径。"

**正确结论**：存在**一条**刷新路径，但只覆盖"终态重建"，不覆盖"瞬时重连"：

- 重建路径：`ChatViewModel.kt@32069（L798-809）` —
  `if (engine.connectedUrl.value != null) { val relay = engine.relayState.value; if (relay != RelayState.Error && relay != RelayState.Kicked && relay != RelayState.Closed) { return true }; engine.disconnect() }`
  随后 `ChatViewModel.kt@33364（L830）` — `engine.ensureSession(url, token)`。
- 该路径之所以能刷新，是因为 `EnginePort.kt@17106（L403）` —
  `private fun disposeLocked() { ... _connectedUrl.value = null; connectedToken = null; connectedAuthToken = null; ... }`
  → 下一次 `ensureSession` 的快路径（`EnginePort.kt@11948（L278）`）必然不命中。
- 但**瞬时断线**（`RelayState.Reconnecting`/`Waiting`）时 `ensureReady()` 直接 `return true`，Rust 的 `do_connect` 用 `params.relay_ws_uri()`（`relay_client.rs@23837（L540）`）复用同一个 `auth_token`，不会重新交换。

**影响**：故障面比 03 描述的小一点（用户手动重试/刷新页面会自愈），但"长会话 + 瞬时断线"仍会卡在过期 token 上；且**同一 URL + 同一 JWT 重复调用 `ensureSession` 也不会刷新**（快路径只看 url/jwt）。

### 2.2 `pair_status` 非 `matched` 即被当作 `waiting_terminal`（03 的机制描述错位）

**03 原文**（§4.2 注）："`pair_status` 缺失或非字符串 → 两个分支都不命中，只更新 `lastPairStatusAckAt`。"

**正确结论**：

1. 网页/桌面的映射函数是"二分"的，不是"三分"：
   `WEB:src-uVqBd_Eq.js@54488` — `function fo(e){return e===\`matched\`?\`paired\`:\`waiting_terminal\`}`
   `DESKTOP:chunk-CJROBAMZ.js@816634` — `function cme(t){return t==="matched"?"paired":"waiting_terminal"}`
2. 因此 `undefined`/`null`/任意非法字符串都会走 **waiting** 分支：
   `WEB:index-DbEHT6Gg.js@6755327` — `applyPairStatus(e){if(this.lastPairStatusAckAt=Date.now(),this.emitDiagnostic({type:\`pair-status\`,pairStatus:e,state:this.state}),cee(e)===\`waiting_terminal\`){...}if(e===\`matched\`){...}}`
3. 这类帧实际**到不了** `applyPairStatus`：`pair_status` 在 schema 里必填（`WEB:src-uVqBd_Eq.js@53259` — `_({type:g(\`auth_ack\`),device_sid:Za,terminal_sid:y().optional(),pair_status:eo,server_ts:H.optional()})`），`ro()` 解析失败返回 null → 整帧丢弃。

**为什么重要**：03 把 Rust 的行为写成了三端共性。Rust 的 `apply_pair_status`（`relay_client.rs@31791（L718）`）确实是"两个 `if` 都不命中则什么都不做"，与网页/桌面**不同**。若将来放宽 schema 或改走非 zod 路径，两者会分叉（网页会误判为 waiting 并起 30s 终态计时）。

### 2.3 Rust 的 KICKED 首轮恢复条件是 `Authenticating || transient`，不是只看关键词

**03 原文**（D7 表）："额外引入 `message` 关键词启发式（conflict/duplicate/already connected/another connection）判定 transient（@795-818）；非 transient 直接 kicked。"

**正确结论**：`relay_client.rs@34858（L795）` —

```rust
if code == Some("KICKED") {
    let detail = message.clone().unwrap_or_default().to_lowercase();
    let transient = detail.contains("conflict") || detail.contains("duplicate")
        || detail.contains("already connected") || detail.contains("another connection");
    if !self.kick_recovery_attempted
        && (self.state == RelayState::Authenticating || transient)
    {
        self.kick_recovery_attempted = true;
        out.push(RelayEvent::Failure(RelayFailure::new("session-conflict", message)));
        self.reconnect(transport, now_ms, out);
        return;
    }
    self.set_state(RelayState::Kicked, out);
    ...
}
```

即：`authenticating` 态下**无条件**恢复一次；其它状态下才依赖关键词。同时 `kick_recovery_attempted` 只允许一次，且**没有** 60s 窗口、**没有** 2–5s 抖动延迟（用的是 `schedule_reconnect` 之外的 `reconnect`，即立即重连）。

---

## 3. MISSING：03（15:52 版）没覆盖但源码存在的分支/字段

| # | 分支/字段 | 坐标 + 原文 | 影响 |
|---|---|---|---|
| M1 | **`data` 入站帧的三端门槛不同**：网页要求 `authenticated && state==='paired'`；桌面要求 `authenticated && state==='paired'`；Rust **无任何门槛** | 网页 `WEB:index-DbEHT6Gg.js@6755164` — `case\`data\`:if(!this.authenticated\|\|this.state!==\`paired\`)return;this.handleDataPayload(n.payload);break;`；桌面 `DESKTOP:index.js@381846` — `else if(t.type==="data"&&this.state==="paired")`；Rust `relay_client.rs@30748（L692）` — `Some("data") => { if let Some(Value::Object(payload)) = frame.get("payload") { out.push(RelayEvent::Payload(payload.clone())); } }` | Rust 在 Authenticating/Waiting 阶段收到 `data` 也会向上层投递；若服务端/中间人提前推帧，会绕过配对门禁 |
| M2 | **Rust 有 100 条出站队列，网页/桌面没有** | Rust `relay_client.rs@43153（L1001）` — `if self.state != RelayState::Paired \|\| self.socket.is_none() { if self.outbound_queue.len() < MAX_OUTBOUND_QUEUE { ... push } return; }`；网页 `WEB:index-DbEHT6Gg.js@6752776` — `this.state!==\`paired\`\|\|this.staleWaitingCount>0?{kind:\`unavailable\`}`；桌面 `DESKTOP:index.js@379158` — `this.state!=="paired"\|\|this.socket?.readyState!==qa.OPEN?{kind:"unavailable"}` | 行为差异：网页/桌面未配对时**直接失败**，Rust **静默入队**并在 matched 后冲刷。属旧 Dart 基线设计 |
| M3（**03 修订版 D15 已覆盖，本轮独立复现确认**） | **Rust waiting 超时的 reason 是 `invalid-mobile-connection`，网页是 `desktop-disconnected`** | Rust `relay_client.rs@39975（L920）` — `out.push(RelayEvent::Failure(RelayFailure::new("invalid-mobile-connection", Some("Desktop did not match this mobile connection before the waiting timeout."))))`；网页 `WEB:index-DbEHT6Gg.js@6757617` — `this.state===\`waiting\`&&this.enterTerminalFailure(H9(\`desktop-disconnected\`,\`Desktop did not match this mobile connection before the waiting timeout.\`))` | 同一句文案、不同 reason 码；上层若按 reason 分支会走错 |
| M4 | **网页心跳 ack 超时后的动作不是"重连"，而是"关 socket + 等抖动 + connect"**（`reconnectAfterStaleWaiting`） | `WEB:index-DbEHT6Gg.js@6750091` — `this.heartbeat=new Kr(()=>this.sendPairStatusQuery(),()=>this.reconnectAfterStaleWaiting(this.getReconnectJitterMs()),{...})`；`WEB:index-DbEHT6Gg.js@6757119` — `getReconnectJitterMs(){let e=uee(this.options.heartbeatIntervalMs??5e3,this.options.heartbeatJitterMs);return Cr(this.options.reconnectJitterMs??Math.min(e,2e3))}` | 网页的 15s 超时后不是走指数退避，而是 0–2s 抖动后立刻重连；Rust 走 30s + 探针 + 退避 |
| M5 | **Rust 的 30s ack 超时实际是"两拍"：先发一次探针，下一拍才重连 → 有效检测约 40s** | `relay_client.rs@41601（L961）` — `if now_ms - self.last_pair_status_ack_at > HEARTBEAT_ACK_TIMEOUT_MS { if !self.stale_probe_sent { self.stale_probe_sent = true; ... } else { ... self.reconnect(...); return; } }` | 03 说"ack 超时 30s 会让掉线检测慢一倍"，实际是慢约 2.7 倍（15s → ~40s） |
| M6（**03 修订版 D16 已覆盖，本轮独立复现确认**） | **网页的 `staleWaitingCount` / `scheduleStaleWaitingRecovery` 是死代码**（只被清零与读取，从未自增；`scheduleStaleWaitingRecovery` 定义 1 次、调用 0 次） | `Buffer` 实测：`staleWaitingCount++` 0 次、`staleWaitingCount+=` 0 次；`scheduleStaleWaitingRecovery` 仅命中定义处 `WEB:index-DbEHT6Gg.js@6757721` | 网页不存在"stale waiting 自动重连"这套机制；Rust 的 Rewait 20s 定时器在 3.16.1 网页里**无对应物**（只在 Dart 基线里） |
| M7 | **网页有页面生命周期挂起/恢复：`onSuspend → relay.suspend()`，恢复走 `recoverConnection`** | `WEB:index-DbEHT6Gg.js@6830577` — `let U=q9n({documentTarget:document,windowTarget:window,onSuspend:()=>{ee.suspend(),te.suspend()},onRecover:e=>{B(e)}})`；`suspend()` 体：`WEB:index-DbEHT6Gg.js@6752932` — `this.state!==\`paired\`&&...\|\|(this.authenticated\|\|this.closeSocket(),this.hiddenStartedAt=Date.now(),this.tokenController?.abort(),...this.setState(\`suspended\`))` | Android 切后台的行为没有对齐对象；`suspend` 只对"未认证"的连接关 socket，已 paired 的保持 |
| M8（**03 修订版 §10-C3 已覆盖，本轮独立复现确认**） | **无 JWT 时网页直接抛 `auth-required`（不是网络错误）** | `WEB:index-DbEHT6Gg.js@6768719` — `return t?this.options.token(t,e):Promise.reject(new In(\`auth-required\`))` | 03 §5.3 只列了"token 或 device 列表返回 401"，漏了"本地无 JWT"这一触发源 |
| M9 | **网页/桌面都先量字节再 `JSON.parse`，Rust 是先 `serde_json::from_str` 无门限** | 网页 `WEB:index-DbEHT6Gg.js@6754592` — `if(v9n(e)>Qt.maxPhysicalFrameBytes){this.options.onRawTransportFault?.(\`remote.rpcFrame.envelopeTooLarge\`);return}`；桌面 `DESKTOP:index.js@380317` — `if((Array.isArray(c)?c.reduce((p,w)=>p+w.byteLength,0):c.byteLength)>xd.maxPhysicalFrameBytes){...return}`；Rust `relay_client.rs` 全文 `MAX_PHYSICAL_FRAME_BYTES`/`envelopeTooLarge`/`1024 * 1024` 命中 **0** 次 | 03 的 D13 说对了这一点（CONFIRMED），但没给桌面侧坐标；桌面同样是"先量后解" |
| M10 | **网页出站也有 1 MiB 门限，桌面没有** | 网页 `WEB:index-DbEHT6Gg.js@6761774` — `send(e){if(this.socket?.readyState!==this.WebSocketCtor.OPEN)return!1;let t=JSON.stringify(e);return new TextEncoder().encode(t).byteLength>Qt.maxPhysicalFrameBytes?!1:this.sendSerialized(e,t)}`；桌面 `DESKTOP:index.js@382835` — `send(t){this.socket?.readyState===qa.OPEN&&this.socket.send(JSON.stringify(t))}` | 上行超限时网页静默丢帧，桌面上报错由对端处理 |
| M11 | **Kotlin 只按 `hash` 判定 legacy，不看 `t`**；网页是 `hash` 或 `t` 任一即拒 | `PairingUrl.kt@4626（L83）` — `fun hasPassHash(raw: String): Boolean = split(raw)?.let { queryParams(it.query).value("hash").isNotEmpty() } ?: false`；`EnginePort.kt@13868（L318）` — `if (PairingUrl.hasPassHash(url)) return null` | 只带 `t`、不带 `hash` 的旧链接：网页直接拒绝，Android 会当成 token 模式去换 token |
| M12 | **`mid` 的三种处理细节**：网页 `trim()` 后非空才追加且**追加原值**；桌面无条件追加（`undefined` 会被 URLSearchParams 强转为字符串 `"undefined"`）；Rust `Option` 非空即追加但**不 trim** | 网页 `WEB:index-DbEHT6Gg.js@6750884` — `this.options.deviceMid?.trim()&&r.searchParams.set(\`mid\`,this.options.deviceMid)`；桌面 `DESKTOP:index.js@379826` — `i.searchParams.set("mid",this.options.deviceMid)`；Rust `connection_params.rs@22519（L512）` — `if let Some(mid) = &self.device_mid { parameters.push(("mid", mid.as_str())); }` | 03 §3.2 表格已覆盖前两者（CONFIRMED），但漏了 Rust 不 trim：`mid=" "` 时 Rust 会发 `mid=%20`，网页不发 |

---

## 4. 旧基线残留清单（本仓无 3.16.1/3.16.3 对应物）

| # | 残留 | 本仓坐标 | 权威源的实际行为 |
|---|---|---|---|
| R1 | WebSocket 关闭码映射 4004/4009/4010/4011/4012/4013 | `relay_client.rs@5230（L125-135）` | 网页/桌面完全忽略 code（§1 #8a） |
| R2 | 关闭码决定"未配对是否重连"（未配对 + 非 desktop-disconnected → 直接终态 Error） | `relay_client.rs@37607（L859-867）` | 网页/桌面**无条件** `scheduleReconnect()` |
| R3 | legacy `/ws` + `auth_challenge`/`auth_response` + HMAC proof + `role:"terminal"` + 毫秒 `client_ts` | `connection_params.rs@21968（L493-497）`（`/ws`）、`relay_client.rs@25278（L578-586）`、`relay_client.rs@28238（L648-682）`、`relay_client.rs@17876（L414-419）`（ms） | 3.16.1 网页只走 `/api/v1/remote/ws2` + 秒级 `client_ts`，且拒绝 hash/t |
| R4 | `parse()` 接受 `hash`/`t`（不拒绝） | `connection_params.rs@20716（L464-468）` | 网页 `Ja` 见任一即返回 null |
| R5 | 心跳 10s / ack 30s / waiting 态隔拍 20s | `relay_client.rs@5674（L138-140）`、`relay_client.rs@41492（L958）` | 5s±1s / 15s（三端一致） |
| R6 | `Rewait` 20s 定时器（"已配对后又变 waiting 就强制重连"） | `relay_client.rs@32458（L734-739）`、`relay_client.rs@40554（L934-942）` | 网页该机制是死代码（M6），桌面完全没有 |
| R7 | `DEAD_LINK_THRESHOLD_MS = 25s` + `poke()` | `relay_client.rs@6330（L149）`、`relay_client.rs@45070（L1052-1091）` | 网页用 `suspend()`/`recoverConnection()`（M7），桌面无此概念 |
| R8 | 出站队列 100 条 + `flush_outbound_queue` | `relay_client.rs@6463（L151）`、`relay_client.rs@43153（L1001-1045）` | 网页/桌面未配对时直接返回 `unavailable`（M2） |
| R9 | KICKED 的 message 关键词启发式 + `kick_recovery_attempted` | `relay_client.rs@34858（L795-819）` | 网页/桌面用 60s 时间窗 + 2–5s 抖动（§2.3） |
| R10 | 失败 reason 命名：`auth-failed` / `wrong-param` / `relay-internal` / `kicked` | `relay_client.rs@34327（L781-814）` | 网页：`device-not-owned` / `invalid-mobile-connection` / `session-conflict`；桌面：`kicked` state + `relay-unavailable` |
| R11 | 退避 1s→2→4→8→15s | `relay_client.rs@47222（L1100-1102）` | 网页 500ms→10s 无抖动；桌面 1s→30s ±20% |
| R12 | waiting 超时 reason = `invalid-mobile-connection` | `relay_client.rs@39975（L920）` | 网页 = `desktop-disconnected`（M3） |
| R13 | 没有 `authenticated` 布尔量（状态机只有 state） | `relay_client.rs` 全文 `grep authenticated` 仅注释/测试 | 网页/桌面都有独立 `authenticated` 标志，且 `data`/`pair_status_ack` 依赖它 |

---

## 5. 网页/桌面证据混淆清单

| # | 03 的位置 | 问题 | 处置 |
|---|---|---|---|
| X1 | 开篇前提 + §4 "网页与桌面逐字相同" | 桌面是 **3.16.3**，网页是 **3.16.1**；本轮逐条比对后确认两者**字段集合与类型逐字对应**（网页 `_({auth_init/pair_status_query/data/auth_ack/pair_status_ack/error})` vs 桌面 `fB/hB/yB/bB/SB/vB/xB`），但**不能由此推断"契约相同"**——只能说明这两个版本之间协议形状未变 | 结论可用，但所有"3.16.1 桌面行为"的表述应改称"3.16.3 桌面行为" |
| X2 | §2 时序图 ⑦ "清 waiting/rewait 定时器；重连计数归零；**冲刷积压 payload**" | "rewait"与"冲刷积压"是 **Rust/Dart** 概念；网页 matched 分支（`WEB:index-DbEHT6Gg.js@6755792`）只清 waiting/stale/offline/reconnect 四个定时器 + `retry.acknowledged()`，**没有** rewait，也**没有**队列 | 时序图应标注"Rust 独有" |
| X3 | §5.2 表 "未配对且未 `desktop-disconnected` → 不重连（`relay_client.rs@37607（L859）`）" | 这是【本仓】列，没问题；但同表把网页/桌面与 Rust 并排放，容易读成"三端都在解析 code" | 建议拆表 |
| X4 | §4.2 注 "`pair_status` 缺失…只更新 `lastPairStatusAckAt`" | 把 Rust 行为写成三端共性（§2.2） | 已纠错 |
| X5 | D6 "权威【桌面】`main/index.js@380091` 同语义" | 380091 是 `this.send({type:"auth_init"`；桌面真正的 ack 校验在 **381367** | 坐标修正（语义本身正确） |
| X6 | D7 "Rust `KICKED` … 非 transient 直接 kicked" | 见 §2.3 | 已纠错 |
| X7 | §5.1 "末尾的 `enterTerminalFailure('relay-unavailable')` 是**不可达**的兜底" | 判断正确（`code` 被 zod 限死五值）——但注意 Rust 侧这条分支**可达**（未知 code 会落进去），不能把这个"不可达"结论迁移到 Rust | 需在实现时区分 |
| X8 | §5.2 桌面列 "另上报 `noteWebRemoteRelayConnectAttempt` 遥测" | 3.16.3 `out/main` 内**找不到该符号名**；close 回调里确实有一次统计调用 `Al(...)`（`DESKTOP:index.js@380623` 内），但函数名无法佐证。03 修订版 §10-C1 已自行改标 UNVERIFIED | 保留"有遥测调用"、删除具体符号名 |

---

## 6. 最终可信结论集（可直接用于实现）

以下每条都有 §1 的坐标支撑，可作为实现依据：

**token 交换**
1. `POST {origin}/api/v1/remote/token`，`Authorization: Bearer <OAuth JWT>`，`Content-Type: application/json`，body `{"role":"terminal"}`；超时 15s（`AbortSignal.timeout(15e3)` 与外部 signal `any` 组合），`redirect:'error'`。
2. 响应必须是 `{code:0,msg:string,data:{token:<^[A-Za-z0-9_-]{21}[AQgw]$>,expires_in:正整数}}`。
3. 错误分级：401 → `auth-required`（不可重试，终态）；`status>=500` → `relay-unavailable` **可重试**；其它非 2xx / JSON 解析失败 / schema 不符 → `relay-unavailable` **不可重试**（终态）；网络异常 → `relay-unavailable` **可重试**。
4. **每次建连都必须重新换 token**（网页/桌面都如此）。`expires_in` 不参与任何逻辑。
5. 无 JWT 时不要发请求，直接按 `auth-required` 处理（网页 `Promise.reject(new In('auth-required'))`）。

**ws2 建连**
6. `wss://<origin>/api/v1/remote/ws2?token=<22字符>[&mid=<mid>]`，无 subprotocol、无自定义 header、无 Authorization。
7. 握手超时 15s（网页 `mee`=15e3；桌面 `15e3`）。超时/失败一律按"可重试网络错误"处理，**不要**假设能读到 HTTP 状态码。
8. 入站帧先量 UTF-8 字节数，>1 MiB 直接丢弃（`envelopeTooLarge`），再解析 JSON，再按 `type` 分派；`type` 非字符串或缺失 → 静默丢弃。

**帧契约（上行，均为 strictObject：多一个键即非法）**
9. `{"type":"auth_init","device_sid":<≤128 字节>,"meta":{platform?,version?,name?},"client_ts":<Unix 秒>}` — **无 `role`**。
10. `{"type":"pair_status_query","client_ts":<秒>}` — **不带 `device_sid`**。
11. `{"type":"data","payload":<任意 JSON>,"client_ts":<秒>}`。

**帧契约（下行）**
12. `auth_ack`：仅当本地 `state==="authenticating"` **且** `device_sid` 等于本地值时接受，否则**整帧丢弃**（不断连、不报错）；接受后 `authenticated=true`、清握手定时器、重置重连计数。
13. `pair_status_ack`：仅当 `authenticated` 时处理（网页还要求 `state!=="suspended"`）。**Rust 当前无此门槛**（见 6b）。
14. `pair_status` 只有 `waiting`/`matched`，必填；映射为 `matched→paired`，其它→`waiting_terminal`。
15. `error.code` 只有五值 `AUTH_FAILED|WRONG_PARAM|KICKED|INTERNAL|DEVICE_OFFLINE`；未知值整帧丢弃。
16. `data` 仅当 `authenticated && state==="paired"` 才转发给上层。**Rust 当前无门槛**（见 M1）。

**状态机时序**
17. 心跳 5s±1s（`min(1000, interval*0.2)`），ack 超时 15s；ack 超时后网页的动作是"关 socket → 抖动 0–2s → connect"。
18. waiting 首次进入起 30s 计时 → `desktop-disconnected` 终态（**Rust 现状发的是 `invalid-mobile-connection`，见 M3，需改**）。
19. `DEVICE_OFFLINE`：立即重连 + 起 15s 宽限；15s 内未 matched → `desktop-disconnected` 终态。
20. `KICKED`：60s 窗口内第二次 → `session-conflict` 终态（state=kicked）；否则 2–5s 抖动后重连。
21. `INTERNAL`：已 paired（或 hadPairedSession 且 waiting）→ 保 socket 转 waiting 并重启心跳；否则立即重连。
22. `AUTH_FAILED` → 终态 `device-not-owned`；`WRONG_PARAM` → 终态 `invalid-mobile-connection`。
23. 退避：网页 500ms×2^n 上限 10s 无抖动；桌面 1s×2^n 上限 30s ±20%。
24. 关闭码无语义，一律重连（除 `intentionallyClosed`）。

**链接解析**
25. URL 出现 `hash` 或 `t` → 直接判 `invalid-mobile-connection`（3.16.1 网页行为）。
26. 缺 `sid` → 进入设备列表流程（网页 `openDevices`），不是失败页。

---

## 7. 未解问题（客户端源码无法闭合）

1. **服务端是否用同一套 zod schema 校验上行帧**。这决定 `pair_status_query.device_sid`（Rust 多发）是否必然触发 `WRONG_PARAM`。客户端侧只能证明"自己的入站校验是 strict"。需要一次真实 `WRONG_PARAM` 或抓包反证。
2. **短 token 是否一次性 / 有效期多长**。`expires_in` 三端都不读；无法从源码区分"一次性"与"过期"。需 `curl`/`websocat` 用同一 token 连两次。
3. **ws2 升级阶段是否会返回 401**，以及条件（token 过期/被消费/mid 不匹配/Origin）。网页用浏览器 WebSocket、桌面用 `ws` 且无 `unexpected-response` 监听，客户端**结构性不可见**。
4. **`terminal_sid` 的用途**。网页/桌面 schema 有该字段但代码从不读取（`WEB:src-uVqBd_Eq.js@53273`、`WEB:src-uVqBd_Eq.js@53370` 仅出现在 schema；主包 `terminal_sid` 命中 0 次），Rust 也不解析。是否为"同设备多终端互踢"句柄未知。
5. **`DEVICE_OFFLINE` 15s 宽限与 `INTERNAL` 的可恢复判定**，哪种与线上服务端实际行为匹配。三端三种策略（网页宽限+终态 / 桌面退避重连 / Rust 只上报）。
6. **`auth_init.meta` 是否被服务端用于版本门控**。桌面有 `parseSemver`/`minCompatibleVersion`（`DESKTOP:chunk-CJROBAMZ.js@818502` 的 `NC`），但那是桌面自身的更新检查，与 relay 的 `meta.version` 无代码关联。
7. **桌面 3.16.3 与 3.16.1 的逐字节 diff**。本轮只能证明"3.16.3 的 schema 文本与 3.16.1 网页逐字相同"，不能证明 3.16.1 桌面也如此。
8. **`staleWaitingCount` 在网页为何保留死代码**（M6）——是否由别的 bundle/版本注入？本轮只在主包内验证。

---

## 附：本轮读取方式与清理

- 网页：`Buffer.indexOf` 定位 → `Buffer.slice` 取原文；`source-index.md` 的 SHA-256 已复核一致。
- 桌面：`node tools/asar.mjs dump out/main/index.js|chunk-CJROBAMZ.js` 到系统临时目录后按字节读取；
  **未执行任何 asar 内代码**；临时目录 `%TEMP%/dz` 与辅助脚本 `%TEMP%/rd.js|find.js|mapx.js|wb.js` 已在收尾时删除。
- 未联网、未使用真机、未修改任何源码或 Round 1 报告。
