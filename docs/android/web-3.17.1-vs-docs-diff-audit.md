# 3.17.1 网页快照 vs 存量文档 差异审计（第二双眼睛）

> 只读差异审计。快照（唯一事实来源）：`E:\Zcode_android\Reference_web\3.17.1\`（2026-10-30 抓取）。
> 主包 `assets/index-Dwhx77cK.js`（6,986,617 B）；schema `assets/validation-B0HfQ5gr.js`；
> 协议常量 `assets/parts-Ba2kLaRj.js`；共享块 `assets/src-BSClmRM9.js`；i18n `assets/IntlProvider-CneXitqp.js`；
> 样式 `assets/index-BgVjt5KE.css`；入口 `index.html`；日志块 `assets/officeFilePreview-BPfDbyPX.js`。
> 下文证据缩写：**主包**=index-Dwhx77cK.js、**校验**=validation-B0HfQ5gr.js、**parts**=parts-Ba2kLaRj.js、
> **src**=src-BSClmRM9.js、**i18n**=IntlProvider-CneXitqp.js、**CSS**=index-BgVjt5KE.css、**HTML**=index.html、**OFP**=officeFilePreview-BPfDbyPX.js。
> 偏移均为 `grep -bo` / `Buffer.indexOf` 字节偏移。
> 对照文档：`docs/android/3.17.1-anchor-audit.md`（下称【锚点审计】）、`docs/android/22-*.md`、`23-*.md`、
> `archive/pre-3.16.1-auth/docs/remote-v4/`（10、13–21、24、28、49）、`docs/WEBVIEW-SHELL.md`、
> `docs/android/pre-device-review2-kotlin.md`、`pre-device-review2-inject.md`（只作壳侧现状交叉参照）。
> 凭证纪律：本文无任何真实 sid/hash/mid/token；URL 参数一律以参数名表述。

---

## 0. 一句话结论

【锚点审计】的 77 条锚点**逐行重验后 75 条成立**，真正错的只有 2 处细节（#13 `#root` 偏移、#16 `aria-haspopup` 的命中数与偏移）；
但它对 **relay 传输层的描述已经整体过时**：3.17.1 把 relay 重写为 **V2 token 传输**
（`WebRemoteControlV2ConnectionError`，校验:151,7xx）——WS 地址改 `/api/v1/remote/ws2`、鉴权改「JWT 换短时 token + `?token=`」、
`auth_challenge`/HMAC proof 整族消失、`auth_init` 去掉 `role`、`client_ts` 从毫秒改**秒**、心跳 10s→**5s**、ack 看门狗 30s→**15s**、
新增 15s 握手超时——这些全部不在【锚点审计】与 13 篇逆向规格的任何一篇里。
协议帧族从 19 字面量扩到 **30**，`tasks[]`（16 键字段表）从 bootstrap 与 workspace-list **双双删除**。
存量文档"关于网页行为/结构/协议"的断言里，**协议面约 1/3 已失效**；DOM/存储/i18n 锚点面几乎全部存活。

---

## 1. 方法与自查轮次

1. 通读全部对照文档，提取可被 bundle 证伪的断言（选择器/存储键/CSS/matchMedia/URL/事件/全局名/帧与字段/常量/流程）。
2. 逐条在 3.17.1 快照定位：`grep -o -b` 拿字节偏移，`node -e readFileSync(...).subarray(off-N,off+M)` 读 ±300–700 字节上下文；
   grep 不到的换同义词/拼接变体/跨 chunk 扫描（minify 别名用 `import{X as Y}from"..."` 导出表反解）。
3. 自查轮次（详见 §5）：初验 4 批（A relay/B schema/C 状态机/D 机制存储路由）+ **15 轮自查**，
   其中 R14、R15 连续两轮零新发现，收尾。反向验证 10 条"已变"结论全部维持（§5 R2）。

---

## 2. 断言清单

判定：**成立**｜**已变**（页面改版导致断言失效）｜**新增**（3.17.1 新出现、文档未记）｜**矛盾**（文档间互相矛盾，以字节为准）｜**修正**（【锚点审计】自身需改）｜**无法判定**（说明原因）。

### A. relay 传输层与鉴权（对照 16 篇 §1/§2、49 篇 §1、24 篇 §1.0）

| # | 来源 | 断言 | 判定 | 证据 | 影响 |
|---|---|---|---|---|---|
| A1 | 16§2.1/49§1.2 | `auth_init` 含 `role:'terminal'` | **已变** | 校验:149,716 `Up=O({type:N(\`auth_init\`),device_sid:Rp,meta:zp.optional(),client_ts:Ip.optional()})` 无 role；发送点 主包:6,904,013 亦无 role（`role:'terminal'` 仅剩 HTTP 换 token 的 body，主包:6,916,218） | 壳/原生承载若照 16 篇发 `role` 字段，属多余键（V2 顶层无 schema strict 与否未知，但发送侧形状已变） |
| A2 | 16§2.1 | `meta:{platform:'web',version,name:'mobile-browser'}` | 成立 | 主包:6,904,013–6,904,140（`mobile-browser` @6,904,124 区；`cir()` @6,901,396 同形） | 无 |
| A3 | 16§2.1 | `client_ts:Date.now()`（毫秒） | **已变** | 主包:6,904,1xx `client_ts:Math.floor(Date.now()/1e3)`；schema `Ip=C().int().min(0).max(253402300799)`（=9999 年的**秒**）校验:149,365；`sendPairStatusQuery` 主包:6,910,466 同秒；`payloadSerializer.prepare(e,Math.floor(Date.now()/1e3))` 主包:6,905,5xx | **凡按毫秒发 `client_ts` 的实现对端将按秒解释**；壳侧协议实现必须同步 |
| A4 | 16§2.1 | 握手为 `auth_challenge`→`auth_response`（HMAC-SHA256 proof，base64url） | **已变（消失）** | 全快照 `auth_challenge`/`auth_response`/`calculateProof` 0 命中；`challenge` 仅剩 OpenID discovery schema 主包:108,943/110,782；V2 错误类 `WebRemoteControlV2ConnectionError` 校验:151,8xx | 13 篇/16 篇的 proof 算法、`passHash`、nonce 流程全部作废 |
| A5 | 16§1/49 | relay WS = `wss://zcode.z.ai/ws`（常量 `Dv`），chatglm 特例 | **已变** | 校验:125,7xx `Ld()`/`Hd()`：`relayWsUrl = ${wss origin}/api/v1/remote/ws2`；主包:6,968,517 `Mar(){return Fe({endpointOrigin:window.location.origin})}` → **同源** `wss://<host>/api/v1/remote/ws2`；全快照无 `zcode.z.ai/ws` 字面量 | 硬编码旧地址的壳会连不上 |
| A6 | 16§1 | URL 只带 `?mid=`，无 sid/hash | 成立+扩展 | 主包:6,903,5xx `deviceMid?.trim()&&r.searchParams.set('mid',…)`；现另带 `?token=`（A7）；`get('hash')` 0 命中 | 无 |
| A7 | —（盲区） | **新增 `?token=<短时token>`** | **新增** | 主包:6,903,4xx `r.searchParams.set(\`token\`,n)`；token 形状 `Xp`：`/^[A-Za-z0-9_-]{21}[AQgw]$/`+`expires_in` 校验:150,0xx | 壳做原生承载必须走同一 token 流程 |
| A8 | —（盲区） | token 由 `POST /api/v1/remote/token`（body `{role:'terminal'}`，Bearer=JWT）换取 | **新增** | 主包:6,916,218 `fir()`；端点表 `Jp`：register/list/delete=`/api/v1/remote/device`、token=`/api/v1/remote/token` 校验:149,8xx | 同上 |
| A9 | 16§12 | HTTP token/window 通道：`/api/remote-control/*`、`X-ZCode-Mobile-Connection-Id`、URL 参数 `remoteControlToken`/`relayOrigin`、`window-control-ready` | **已变（整族消失）** | 全快照 0 命中（xargs 全 js 扫描） | 16 篇 §12、13 篇 §1 的"两条通道"模型只剩 relay 一条 |
| A10 | —（盲区） | /remote/v4 新增**页内登录+设备选择**控制器（`Oir`） | **新增** | 主包:6,919,4xx 起：`start()` 要求 `auth.getZCodeJwtToken()`（6,920,1xx），无则视图 `login`；无 `sid` 参数进设备选择；`admit()` 回写 `sid/mid/name`（6,922,142）；`chooseOtherDevice()` 删 `sid/mid/name/hash/t`（6,923,1xx） | 壳若绕过登录态直开 /remote/v4 会停在登录/设备视图 |
| A11 | 49§1.2 | `auth_ack` 保留字段=pair_status | **已变** | schema `Kp`：`auth_ack{device_sid,terminal_sid?,pair_status,server_ts?}`、`pair_status_ack{terminal_sid?,pair_status,server_ts?}`、`data/error{server_ts?}` 校验:149,9xx；主包:6,907,2xx `auth_ack` 分支要求 `state==='authenticating'&&n.device_sid===this.options.deviceSid` 才 `authenticated=true` | 入站帧新增 `terminal_sid`/`server_ts`；`auth_ack` 带 device_sid 校验 |
| A12 | 16§7.1 | 顶层 error code 5 值 `AUTH_FAILED/WRONG_PARAM/KICKED/INTERNAL/DEVICE_OFFLINE` | 成立 | 校验:149,679 `Hp=M([...])` | 无 |
| A13 | 16§2.4 | 出站闸门：`state!=='paired'` ⇒ `unavailable`（不排队） | **已变（放宽）** | 主包:6,905,5xx `let n=e.zcode_type==='rpc-frame-ack'&&this.canContinueRetainedRpc(); return this.state!=='paired'&&!n||this.staleWaitingCount>0?{kind:'unavailable'}:…`；`canContinueRetainedRpc`=(suspended\|reconnecting)&&authenticated&&(socket===pairedSocket)&&OPEN 主包:6,908,0xx | suspended/reconnecting 期间 rpc-frame-ack（及同 socket 的 rpc-frame 入站）继续收发 |
| A14 | 16§8 | close 且从未配对 ⇒ `error` 终态+relay-unavailable | **已变** | 主包:6,904,5xx close 分支两路都 `scheduleReconnect()`（`if(this.hadPairedSession){this.scheduleReconnect();return}this.scheduleReconnect()`） | "未配对即终态"不再成立；未配对断线会无限退避重连 |
| A15 | 21§1.3 | `suspend()` 仅 paired/connecting/reconnecting 可进 | **已变** | 主包:6,905,6xx 现含 `authenticating`/`waiting`，且先 `this.authenticated||this.closeSocket()`、abort tokenController、清 handshakeTimer | 16/21 篇状态描述需订正 |
| A16 | 21§1.3 | recoverConnection 快路径：suspended+wasPaired+OPEN ⇒ 转 reconnecting+发 pair_status_query+等 3s，失败才重拨（≤15s） | 成立 | 主包:6,905,7xx 逐字同构（`healthCheckTimeoutMs??3e3`、`waitForPaired(e=15e3)`） | 无 |
| A17 | 16§2.5 | recoverInBackground 跳过 intentionallyClosed/paired/kicked | 成立 | 主包:6,905,9xx | 无 |
| A18 | 16§2.3 | 退避 = `min(1e4, 500×2^n)` | 成立（实现改形） | 主包:6,902,2xx `retry=new An(Math.random,{initialMs:500,maxMs:1e4,jitter:0})`；`nextDelay` 2^min(failures,16) 后夹 maxMs 校验:151,9xx | 无（数值等价） |
| A19 | 16§2.1 | waitingTimeoutMs=30s，到点终态 **invalid-mobile-connection** | **已变** | 主包:6,910,6xx `state==='waiting'&&this.enterTerminalFailure(V9(\`desktop-disconnected\`,\`Desktop did not match this mobile connection before the waiting timeout.\`))`（30s 数值不变） | 失败原因归类变了，壳的文案/归因要跟 |
| A20 | 23§4 | desktopOfflineGraceMs 15s | 成立 | 主包:6,911,3xx `??15e3` | 无 |
| A21 | 16§2.3 | staleWaitingCount 是死代码（无自增） | 成立 | 主包 13 处引用（6,902,137…6,913,311），全部 `=0` 写或 `>0` 读，无自增 | 无 |
| A22 | 16§2.2 | 心跳 10s ±2s（8–12s） | **已变** | 校验:152,196 `im=5e3`、152,203 `am=1e3`；`Sm` 心跳类 校验:154,596 `um(intervalMs??5e3, jitterMs??1e3)`；主包无任何 `heartbeatIntervalMs` 赋值点（反向验证） | **心跳 4–6s**；壳侧"10s 泵补心跳"频率判断需复核 |
| A23 | 16§2.2 | ack 看门狗 30s | **已变** | 校验:154,6xx `ackTimeoutMs??15e3`、常量 `om=15e3` @152,210 | **15s** 无 ack 即 reconnectAfterStaleWaiting |
| A24 | 16§2.2 | 看门狗超时后重连延迟 0–2s | 成立 | 校验:152,218 `sm=2e3`；主包:6,910,4xx `reconnectJitterMs??Math.min(e,2e3)` | 无 |
| A25 | 16§2.1 | 没有独立握手超时定时器 | **已变** | 主包:6,903,6xx `this.handshakeTimer=setTimeout(()=>this.scheduleReconnect(),Je)`；`Je`=校验 `om as Je`=15e3（主包导入表 @61,2xx + 校验导出表） | **新增 15s 握手超时**：open 后未完成 auth_ack 即重连 |
| A26 | 49§1.2 | trace 裁剪只留 `{zcode_type,requestId,bridgeSessionId}`；顶层判别 `tir` | 成立 | 主包:6,897,7xx `$rr`、6,898,4xx `eir`、6,898,5xx `tir`（auth_init 仅留 type——与 A1 一致） | 无 |
| A27 | 16§7.5 | 入站原始帧 >1MiB ⇒ envelopeTooLarge | 成立 | 主包:6,907,0xx `Zrr(e)>Ce.maxPhysicalFrameBytes` | 无 |
| A28 | 49§4.4/16§7.1 | KICKED = 立即终态 | **已变** | 见 C2/C3 | 见 C 组 |
| A29 | 16§7.4 | 通用请求默认超时 10s | 成立 | 主包:6,899,4xx `iir({sendPayload…,timeoutMs:t=1e4})`；超时抛 `desktop-bootstrap-timeout` | 无 |
| A30 | 16§7.4 | workspace-reconnect 120s | 成立 | src:51,946 `Ba=12e4`（导入为主包 `Ia`），用于匹配 `{timeoutMs:Ia}` 主包:6,974,8xx | 无 |
| A31 | 16§7.4 | 请求 id 前缀 7 种 bootstrap/platform/workspace-list/workspace-reconnect/bridge/workspace-bridge/recovery | 成立 | 主包:6,971,486/6,973,971/6,974,214/6,974,653/6,975,009/6,975,193/6,971,486(`recovery` @6,971,486 区) | 无 |
| A32 | 24§4.2 | 请求层恢复重试条件：relay-unavailable，或 recoverTimeout+desktop-bootstrap-timeout | 成立 | 主包:6,898,7xx `nir` | 无 |
| A33 | —（盲区） | `bootstrap-request`/`workspace-bridge-open` 新增可选 `terminalClientSessionId`；bridge-open 另有 `taskHostId?/fileTreeTaskId?/project?{projectId,hostId?,draftId?,reuseDraft?}`（含跨字段 refine） | **新增** | 校验:322,130/322,489；主包发送 6,973,628/6,975,157；schema refine 文本校验:322,5xx | 49 篇字段表缺这些键；均为可选，不发不违规 |
| A34 | —（盲区） | 传输层错误类名 `WebRemoteControlV2ConnectionError` + 新原因 `auth-required`（V2 It 类） | **新增** | 校验:151,8xx；主包:6,916,1xx/6,919,9xx `new It(\`auth-required\`)`；`auth-required` 不在 `OT` 11 值内，属 V2 传输层新原因码 | 壳归因表要加这一条 |

### B. 帧 schema 与枚举（对照 49 篇、14/17 篇、18 篇、13 篇）

| # | 来源 | 断言 | 判定 | 证据 | 影响 |
|---|---|---|---|---|---|
| B1 | 49§0/16§4 | 业务联合 = 19 字面量/21 臂（`uS`） | **已变** | 校验 3.17.1 联合 `RT`（@322,023 起）＝ **30 字面量/36 臂**（36 处 `zcode_type:N(`，枚举扫描）；`zT=RT.safeParse` @校验:324,9xx | 49 篇速查表缺 11 个新帧 |
| B2 | 49§2 | 19 个新字面量名单 | **已变** | 新增 11：`mobile-log`、`mobile-log-pull-request`、`mobile-log-pull-response`、`workspace-create-request/-response`、`task-pin-request/-response`、`task-archive-request/-response`、`mobile-task-view-update`、`mobile-task-view-request`（校验:153,358 区、321,050 区、324,1xx） | 锚点审计只记了 mobile-log 族 + task-view 族，**漏了 workspace-create/task-pin/task-archive 6 帧** |
| B3 | 49§2.1 | telemetry-report 是唯一 `.strict()` 业务帧，event=`Sl`（session_create） | **已变** | 3.17.1 `event:k([fu,Gl])` 双变体：`fu`=session_create（与旧 Sl 逐字同）校验:108,943；**新增 `Gl`=plugin_discovery**（elementName:'plugin_discovery'/eventType:'view'/action∈{store_view,detail_view,search}/client_kind:'mobile'）校验:104,815 | 发 plugin_discovery 遥测是新面；session_create 形状未变 |
| B4 | 49§2.1/14§6.1 | bootstrap-response.result（`sS`）6 键 | **已变（+1 键）** | 校验:320,4xx `NT={windowControlSessionId,desktopAppVersion?,workspaces,initialViewState?,mobileViewState?,mobileTaskViewEpoch?}` | 新增 `mobileTaskViewEpoch` |
| B5 | 49/13 | result.windowControlSessionId 必填 | 成立 | 校验:320,4xx | 补充：页面现在**读它**（3.15.3 relay 路径不读）：状态比较器 主包:2,156,659/2,156,686、状态 UI 2,541,179/2,541,308/2,542,732/2,542,759 → 13 篇"relay 路径零读取"已失效 |
| B6 | 49/13/14/17 | result.tasks 必填（16 键字段表） | **已变（删除）** | `NT` 无 `tasks`；`PT`（workspace-list result）也无 tasks：校验:320,5xx `PT=D({workspaces,activeWorkspaceKey?,activeTaskId?})`；`displayStatus` 在校验 0 命中；主包 2,656,715 的 `displayStatus` 属 coding-plan 购买态（另一域） | **14/17 篇整张 tasks 叶子表失效**；13 篇"任务清单走 workspace-list 族"的结论失效——任务数据只剩 sessions-index 流（B35） |
| B7 | 13§3.2/17 | `tbn` 判据 `displayStatus==='running'||hasBackgroundWork` | **已变（前提消失）** | tasks 不再下发；`hasBackgroundWork` 仅存于 sessions-index 元素 校验:261,594 | 判据应迁移到 sessions-index 的 `phase`/`hasBackgroundWork` |
| B8 | 49§2.1 | workspaces 元素 8 键 | 成立 | 校验:318,647 `DT=E(D({workspacePath,workspaceIdentity?,remoteSessionId?,label,workspacePurpose?,kind,connectionState?,lastConnectionError?}))` | 无 |
| B9 | 49 | viewState=`{activeWorkspaceKey?,activeTaskId?,updatedAt:finite}` | 成立 | 校验:319,924 `AT` | 无 |
| B10 | 49 | deviceInfo 12 键（platform/version/name 必填） | 成立 | 校验:320,086 `MT` | 无 |
| B11 | 49§2.2#13 vs 21§2.3 | **文档间矛盾**：49 篇称页面发 mobile-view-state-update"只带 viewState 不带 deviceInfo"；21 篇称带 `deviceInfo` | **矛盾→以字节为准：带** | 主包:6,974,453 `H.sendPayload({zcode_type:'mobile-view-state-update',viewState:{activeWorkspaceKey,…,updatedAt:Date.now()},deviceInfo:cir({appVersion:…})})` | 49 篇该行判定错误（21 篇对）；壳要仿发时应带 deviceInfo |
| B12 | 49§4.5 | platform method 7 值 | 成立 | 校验:320,5xx `FT=M([isDockerAvailable,…,migrateLegacyCommonMcp])` | 无 |
| B13 | 49§4.1 | 失败原因 `rS` 11 值 | 成立 | 校验:319,087 `OT=M([...11 值逐字...])` | 无 |
| B14 | 49§4.2/16§7.3 | bridge-degraded.reason **4 值** | **已变** | 校验:324,420 现在 **6 值**：+`source-session-replaced`、`terminal-owner-ended` | 壳的 reason→文案映射表少两条 |
| B15 | 49§4.3 | mobile-diagnostic.event 7 值；closeCode 无 nonnegative；可选键含 wasPaired/hiddenDurationMs | 成立 | 校验:324,922 逐字同 3.15.3 | 无 |
| B16 | 49§5.1 | rpc-frame 字段与两条 superRefine 消息 | 成立 | 校验:168,715 逐字（`fragmentIndex must be smaller than fragmentCount` 等） | 无 |
| B17 | 49§5.1 | rpc-frame-ack：bridgeSessionId 必填、ackMessageSeq>0、strict | 成立 | 校验:169,4xx `yh` | 无 |
| B18 | 49 | checksum `{algorithm:'crc32',value:/^[0-9a-f]{8}$/}` strict | 成立 | parts:8,717 `Et`（validation 同构） | 无 |
| B19 | 49§5.2/16§5.2 | bd 预算 1MiB/16MiB/64 片/30s/256 | 成立 | 校验:168,107 `dh={maxPhysicalFrameBytes:km.maxFrameBytes,maxMessageBytes:16*1024*1024,maxFragments:64,assemblyTimeoutMs:3e4,transportIdMaxChars:…}` | 无 |
| B20 | 49§5.2/18§1.1/19§2.1 | Uu/km 常量表（1MiB、逻辑帧 1024/16MiB/32/32MiB/30s、订阅缓冲 500/1MiB、保留 2e3、尾窗 60、rowsRange 200） | 成立+新增 | 校验:154,9xx `km` 逐字，另**新增** `toolOutputFinalHeadBytes/TailBytes:32*1024`、`goalVerificationsRetained:20`、`pendingCommandsDisplayMax:32`、`commandPendingTtlMs:1440*60*1e3`、`idempotencyTablePerSession:512`、`conversationQueryTimeoutMs:1e4` | 常量表整体仍对；新增 6 项 |
| B21 | 16§7.2 | 重放缓冲上限 8MiB | **已变** | 主包:6,849,785 `D9={saturationHighWaterMarkBytes:1MiB,saturationLowWaterMarkBytes:256KiB,replayBufferMaxBytes:2*Ce.maxMessageBytes,replayBufferGraceMs:45e3,…}` ⇒ **32MiB**；收端 clamp 上限同值 主包:6,850,936 | 16 篇数值过时 |
| B22 | 16§7.2 | ack/重放宽限 45s | 成立 | 主包:6,849,847 `45e3` | 无 |
| B23 | 16§7.2 | 饱和高/低水位 1MiB/256KiB | 成立 | 主包:6,849,709 | 无 |
| B24 | 锚点审计§4.3 | mobile-log 帧：`{zcode_type,events[1..100],device?,sessionId?}`；pull-request 含 `acceptEncodings:['gzip']`≤4 | 成立 | 校验:153,358 `gm`/`_m`；限值表 `fm` @152,769（maxEventsPerFrame:100、maxCompressedShardBase64Chars:7e5、maxDecompressedShardBytes:8MiB、maxPullEntries:1e6…） | 锚点审计对这族的描述正确 |
| B25 | 锚点审计§4.3 | pull-response 形状 | 成立+补全 | 校验:153,9xx 共 **4 个成功变体+1 个失败臂**（`success:false{reason?,error}`；gzip 分片变体带 `encoding/data/entryCount`；基础变体带 `truncated?/sources?`） | 锚点审计漏了失败臂与 gzip 分片变体 |
| B26 | 锚点审计§4.3 | /remote/v4 主包内无 mobile-log 收发实现（仅桌面端按钮 testid） | 成立 | 主包唯一 `mobile-log` 命中 @2,625,544 = `web-remote-control-pull-mobile-logs` testid @2,625,520 | 无 |
| B27 | —（盲区） | workspace-create/task-pin/task-archive 9 臂 schema | **新增（schema-only）** | 校验:321,050 `LT=[…]`；主包 0 消费（grep=0） | 新的远控动作面（创建工作区/置顶/归档），移动端应答者待桌面端定义 |
| B28 | 49§2.1 | workspace-bridge-ready.bridge 按 kind 判别（local/remote 字段） | 成立+新增 | 校验:319,7xx `kT`：remote 臂新增 `terminalOwnerId?` 与 `capabilities:{windowProjectDirectory:true?}` | 桥能力位新增 |
| B29 | 49§2.2#7 | workspace-bridge-open 按 bridgeSessionId 匹配（非 requestId） | 成立 | 主包:6,975,157 起 `ce`：`bridgeSessionId:n(=Z9('bridge'))`，`requestId:Z9('workspace-bridge')` 分立 | 无 |
| B30 | 18§1.1 | 会话线 wireVersion:3、deliveryKind∈{initial,online,recovery}、complete/fragment | 成立 | parts:8,717–8,9xx | 无 |
| B31 | 18§1.2 | ops 11 种（row.*5+state.updated+pluginUi.*4+workflowRun.*2） | 成立 | parts:5,291 `gt=w('op',[…])` 逐字 | 无 |
| B32 | 18§1.1 | topic 前缀校验（conversation≠13/sessions-index≠15/workspace-config≠17） | 成立 | parts:12,576/13,151/13,683 | 无 |
| B33 | 18§3 | sessions-index 快照 protocolVersion:1；会话元素字段表 | **已变** | 校验:261,1xx `XS`：**新增 `lastOutcome:['completed','error','interrupted']`**；`pendingInteractionSummary` 变为对象 `{permissionCount,userInputCount}`（`YS`）；`goalStatus` 收敛为 `cb.shape.status` | 18 篇会话元素表需加行 |
| B34 | 16§10.2 | `liveStatus` 是会话索引字段（5 值） | **已变（消失）** | 主包 `liveStatus` 0 命中；状态改由 `phase`（6 值 `draft/prewarming/running/completedSuccess/completedInterrupted/error`，校验:228,058 `Py`）承载 | 16 篇 §10.2 的字段与枚举作废 |
| B35 | 19§2.1 | 附件常量（20MiB/512KiB/64 片/30MiB 预览/2GiB stat/TTL）与 384KiB 实发 | 成立 | 校验:154,9xx `km`（attachmentMaxBytes:20*1024*1024、attachmentChunkMaxBytes:512*1024、attachmentUploadMaxChunks:64、Cm=30MiB @154,7xx…）；主包:264,532 `384*1024` | 无 |
| B36 | 19 | 附件四段式 begin/chunk/commit/abort | 成立 | 主包:266,379/266,441/267,290/267,369 | 无 |
| B37 | 16§6 | 页面 clientHello `{kind:'clientHello',protocolVersion:3,clientId,clientKind:'web'|'desktop',appVersion:'unknown',capabilities:{workspaceHookReviewUi:true,workflowRunDeltas?}}` | 成立+新增 | 主包:248,080 逐字；**capabilities 新增 `historyFirst`**（`t.capabilities.historyFirst===!0?{historyFirst:!0}:{}`）；`hello` schema 能力位新增 `independentPlanState/genUiContext/inputPrediction/residentRowsRange` parts:10,6xx | 能力协商面扩大 |
| B38 | 16§6 | clientId `client-<rand>` 存 `zcode-v4-client-id:v1` | 成立 | 主包:246,957/246,966（`Nle()`） | 无 |

### C. 状态机 / 重连 / KICKED / 版本闸门（对照 16§9、锚点审计§4、21/24 篇）

| # | 来源 | 断言 | 判定 | 证据 | 影响 |
|---|---|---|---|---|---|
| C1 | 锚点审计§4.2 vs 16§9.1 | **文档间矛盾**：锚点审计称"3.17.1 状态集**新增** `kicked`"；16 篇的 3.15.3 状态表本就含 `kicked`（9 值） | **矛盾→锚点审计表述错** | 3.17.1 状态集仍 9 值：idle/connecting/authenticating/waiting/paired/reconnecting/suspended/kicked/error（`setState` 字面量与 `dir` 类字段 主包:6,902,1xx–6,912,5xx）；3.17.1 真正新增的是 **KICKED 重试（kickedDelay）**，不是状态 | 锚点审计 §4.2 该行应改为"新增 KICKED 后的一次重连，而非新增状态" |
| C2 | 锚点审计§4.2 | KICKED 首次 ⇒ `kickedDelay()` 2–5s 重连；60s 内第二次 ⇒ 终态 session-conflict | 成立 | `rm.kickedDelay` 校验:152,054（`<6e4?null:(lastKickAt=e,2e3+Math.floor(random()*3001))`）；`handleRelayError` 主包:6,909,421 | 同锚点审计 |
| C3 | 49§4.4 | KICKED → setState('kicked')+enterTerminalFailure | 成立（终态分支） | 主包:6,912,5xx `setState(e.reason==='session-conflict'?'kicked':'error')` | 与 C2 合并理解 |
| C4 | 锚点审计§4.2 | 终态文案键 `webRemoteControl.failure.kicked`；`NAt` 把 session-conflict+message含kicked 映射为 kicked，否则 sessionConflict | 成立 | 主包:2,543,733 `NAt`；i18n failure 12 键枚举 | 无 |
| C5 | 16§7.1 | INTERNAL → paired(或 hadPaired&&waiting) ⇒ enterWaitingForDeviceAfterRelayError，否则 immediate 重连 | 成立 | 主包:6,909,6xx–6,910,4xx | 无 |
| C6 | 16§7.1 | DEVICE_OFFLINE → recoverFromDeviceOffline（15s 宽限） | 成立 | 主包:6,911,3xx | 无 |
| C7 | 16§7.1 | AUTH_FAILED/WRONG_PARAM → invalid-mobile-connection 终态 | 成立 | 主包:6,909,8xx | 无 |
| C8 | —（盲区） | 新增 `device-not-owned`/`desktop-disconnected` 失败视图 → `resolveLostTarget` 自动换设备重连；`Dir=new Set(['device-not-owned','desktop-disconnected'])` | **新增** | 主包:6,919,3xx `Dir`、6,923,3xx `resolveLostTarget` | 页面对"桌面丢失"有自愈换设备路径，锚点审计未提 |
| C9 | —（盲区） | 新增 bridge 重建重试梯 `pir=[1e3,2e3,5e3]`（`gir(p)`），超预算终态 | **新增** | 主包:6,916,625/6,917,176；消费 @6,971,0xx `z()`（"did not rebuild the workspace bridge within the retry budget"） | 锚点审计未提；壳的看门狗窗口应 > 1+2+5s |
| C10 | 锚点审计§4.1 | isBlocked 门死：sendPayload（`!v.isBlocked()&&H.sendPayload`）、onStateChange（`!(c\|\|v.isBlocked())`）、B() 首行、keydown 捕获 stopImmediatePropagation | 成立 | 主包:6,973,428/6,972,883/6,971,1xx/6,969,435 | 同锚点审计 |
| C11 | 锚点审计§4.1 | 阻断 modal：`<dialog>`+showModal、`aria-labelledby="remote-version-title"`、标题/正文/按钮逐字 | 成立 | 主包:6,955,262/6,955,588/6,955,657/6,955,856/6,956,176；补充：modal 是 `mar()` **命令式 createElement**（主包:6,954,996），不在 #root 内——壳若以 "#root 内" 扫描会漏 | 锚点审计 P0-1 选择器有效；但扫描器别限定在 #root |
| C12 | 锚点审计§4.1 | 闸门比较逻辑（pathname 去尾斜杠 ==='/remote/v4' 且 app_version!==desktopAppVersion ⇒ 未就绪 navigate / 已就绪 onMismatch） | 成立 | 主包:6,925,965 `Pir`；`isPageReady()?onMismatch:navigate` | 同锚点审计 |
| C13 | 锚点审计§6.1 | desktopAppVersion 在主包唯一消费点=闸门 | 成立 | 主包 `desktopAppVersion` 仅 1 处 @6,926,286 | 无 |
| C14 | —（盲区） | 闸门 `onAccepted` 里 `l==='paired'&&V.seed(e.mobileTaskViewEpoch)`（task-view epoch 只在已配对时播种） | **新增** | 主包:6,969,273 | 锚点审计只写了"onAccepted 里 V.seed"，未写 paired 前置条件 |
| C15 | 24§1.0 | 版本闸门 3.15.3 已存在（`s6n`） | 成立 | 3.17.1 `Pir` 与 24 篇逐字同构（含 invalidate 计数） | 锚点审计把它列为"3.17.1 新增"是**不准确**（该机制 3.15.3 已上线，24/28/10 篇均有记录） |
| C16 | 锚点审计§4.4 | 恢复白名单 visible\|pageshow\|online\|resume | 成立 | 主包:6,971,1xx `L=e=>…`；kir 主包:6,924,090 | 无 |
| C17 | WEBVIEW-SHELL§2.6 | TaskViewReporter 在生命周期事件时上报（可见⇒view，隐藏⇒null） | 成立 | 主包:6,924,3xx `fe=kir({…onSuspend:()=>{V.suspend(),H.suspend()},onRecover:e=>{B(e)}})`；`Nir` @6,925,317/6,925,472 | 无 |
| C18 | 锚点审计§4.3 | mobile-task-view-update `{zcode_type,epoch,seq,bridgeSessionId,bridgeGeneration?,view}`；request{epoch}→V.request | 成立 | 主包:6,925,472（发送形状逐字）/6,972,238（request 消费） | 无 |
| C19 | 锚点审计§2.6#48 | kir 生命周期接线（document:visibilitychange/freeze/resume；window:pageshow/pagehide/online） | 成立 | 主包:6,924,090–6,924,3xx 逐字 | 无 |
| C20 | 21§1.2 | A()（恢复主函数）首行守卫含 isBlocked；健康路径只跑 L()（bootstrap 闸门） | 成立 | 主包:6,971,1xx `B=_ir(async e=>{if(J9\|\|c\|\|v.isBlocked())return;…r=!L(e)\|\|x?.isDegraded()===!0;…})` | 无 |
| C21 | 24§2.2 | 恢复选桥规则：preferredWorkspaceKey 优先，否则 mobileViewState（`Ud`/`Ra` 族） | 成立（形状微变） | 主包:6,917,1xx `hir(e,t)`（preferred 命中/activeTaskId 直开/`Ra(e)` 兜底）；注意 `initialViewState` 在主包 **0 命中**（仅存于 schema）——"mobileViewState ?? initialViewState" 的 initialViewState 半边在 3.17.1 页面侧已无消费者 | 13/24 篇"双视图态回退"应改为"仅 mobileViewState 生效" |
| C22 | 锚点审计§5 P0/P1 | 壳侧建议（闸门探测/对齐 KICKED 阈值/URL 带 app_version/pinChatRoute） | 成立（已被壳实施） | pre-device-review2-inject.md：闸门③/versionGateBlocked、DesktopVersion.withAppVersion 全 loadUrl 覆盖、pin 先钉后焚均已落地 | 无需再做 |

### D. 页面机制 / 存储 / 路由 / i18n（对照 20/21/24/28/10/22/23 篇、WEBVIEW-SHELL）

| # | 来源 | 断言 | 判定 | 证据 | 影响 |
|---|---|---|---|---|---|
| D1 | 20§2.2/24§1.1 | 判页唯一开关 `history.state.zcodeMobilePage==='chat'`；pushState 页面自写 | 成立 | 主包:5,256,676 `J6`、5,256,776 `xLn` pushState、5,256,787、5,257,434 返回键、5,258,297 渲染分支 | 无 |
| D2 | —（盲区） | `history.state` 的 key 全集 = {zcodeMobilePage}（无第二键） | 成立 | 主包 4 处 `history.state` 引用（5,256,745/5,256,879/5,257,434/6,960,159），全部只读/透传该键 | 盲区问题正面回答 |
| D3 | 24§1.2 | home/chat 同组件两分支；popstate 重算+refreshKey++ | 成立 | 主包:5,256,8xx 区（popstate 监听）、5,257,137 外壳 props（`renderTaskHome` 等） | 外壳 props 名变了（renderTaskHome 取代旧 mobile home） |
| D4 | 24§1.3/21§2.1 | 槽位键 `zcode-v4-last-session:v1:<ws>`；门控 4 条件；仅 reload 读 | 成立 | 主包:4,845,710（键）、4,846,220 `vAn`（含 `restoreOnStartup===!0` 新条件——同 WEBVIEW-SHELL §2.5 引文逐字） | 无 |
| D5 | WEBVIEW-SHELL§2.5 | `yAn({…enabled:!sn,restoreOnStartup:!!(B&&In)…})` ⇒ /remote/v4 上槽位死代码 | 成立 | 主包:6,044,200 逐字 | 无 |
| D6 | 21§2.1 | mun 钩子（现名 usePaneSessionPersistence 日志） | 成立 | 主包:4,846,7xx `J.warn('[usePaneSessionPersistence] 验证恢复会话失败'…)` | 无 |
| D7 | 20§5.1 | zcode-theme 5 处 | 成立 | 主包:80,586/80,642/2,641,805/6,896,163/6,957,354 | 无 |
| D8 | 锚点审计#34 | zcode-locale-preference | 成立 | 主包:2,715,812 + i18n:969,616 | 无 |
| D9 | 锚点审计#36–40 | zcode-v4-pending-commands:v1 / zcode:auth:jwt-invalid-restart / zcode:coding-plan:embedded / developer-tools+token-debug / hide-scrollbar | 成立 | 主包:2,390,768 / 6,742,184 / 2,641,856 / 6,001,783+6,001,815 / 2,643,404 | 无 |
| D10 | 22§2/28§三 | boot-loading 8 处、theme-surface 由 index.html inline 写、色值 #161616/#f8f8f8、STORAGE_KEY=zcode-theme、DEFAULT=zai-dark | 成立 | HTML:9,025（共 8 处）/4,477（共 7 处）/3,955+3,983/3,842；主包:78,419 | 无 |
| D11 | 10§5#4 vs 22§2.3/15§1 | **文档间矛盾**：10 篇称 `zcode:web-remote-control:bootstrap-shell` 全快照 0 命中；22/15 篇引用它 | **矛盾→以字节为准：存在** | HTML:4,068 `BOOTSTRAP_SNAPSHOT_KEY="zcode:web-remote-control:bootstrap-shell"` | 10 篇该行判定错误（疑只扫了 assets/ 未扫 index.html） |
| D12 | 锚点审计§3 | 新增锚点 data-zcode-bootstrap-theme | 成立 | HTML:7,143/7,410/8,033/9,512 | 锚点审计给的 "@98/@104" 是**行号口径**，字节偏移应为 7,143 起 |
| D13 | 22§3 | 滚动条 14px 规格 + thumb min32 + terminal/pptx 隐藏例外 | 成立 | CSS:448,442/448,655/448,729+448,788/448,875+448,931 | 无 |
| D14 | 锚点审计#17/#45 | scrollbar-hide、scrollbar-gutter:stable | 成立 | CSS:58,272 / 200,730-200,784 | 无 |
| D15 | 锚点审计#12/#24/#25/#41–43 | bg-background-win-alt/bg-header/--color-header/--color-background/--color-border/--color-background-win-alt | 成立 | CSS:114,097/123,786/16,447+420,712+433,911/14,529/15,870/14,762+114,140+419,117；主包:4,403,259/5,258,422/79,075/91,044 | 个别偏移与锚点审计差 ±1–5B（其引用口径偏差，不影响结论） |
| D16 | 锚点审计#46 | 手机断点收窄为 `(max-width:767px) and (hover:none) and (pointer:coarse)` | 成立 | 主包:3,226,116+3,226,139+3,226,157（三条件同段）；第二处 3,310,777（无 max-width 的纯粗指针探测，CUA 域） | 同锚点审计；补充：**CSS 内已无 767px 媒体查询**（grep=0），断点只存在于 JS |
| D17 | 锚点审计#47 | prefers-color-scheme | 成立 | HTML:6,824、CSS:306,258 | 无 |
| D18 | 锚点审计#26–32 | 五条文案（向 ZCode 提问/新建任务/继续输入以排队后续修改/提出后续修改要求/初始化任务中）+任务会话 | 成立 | i18n:335,914/14,263/336,139/336,079/336,202/150,922+440,859 | 无 |
| D19 | 20§4 | 六态 status.*+statusDetail.*、statusTag.ready/phone、triggerStatus.* | 成立 | i18n（status 6 键、statusTag 2 键、triggerStatus 6 键）；主包:2,542,478/2,542,637 | 无 |
| D20 | 锚点审计§4.4 | mobileShell.reconnecting @5,260,983 | 成立 | 主包:5,260,983 | 无 |
| D21 | —（盲区） | **新增 i18n 族 `authRequired.{title,description,login}`** | **新增** | i18n（3 键×2 语言） | 登录态文案，锚点审计未提 |
| D22 | 20§5.2 | `mobileHome.*` 22 键 + 偏好键 `zcode-web-remote-control-mobile-task-home-preferences` | **已变（消失）** | i18n `mobileHome` 0 命中；主包偏好键 0 命中（`task-home`/`TaskHome` 组件名 @5,257,137 为 prop 名） | 20 篇 §2.3 的偏好键/键族失效；手机首页改由 `renderTaskHome` prop 渲染 |
| D23 | 20§5.2 | webRemoteControl 键数 104 | **已变** | i18n 唯一键约 89（同正则）；族构成：+authRequired(3)、−mobileHome(22) | 数字级断言过期，不影响功能 |
| D24 | 19§1 | 两套草稿键并存（v4 完整 CRUD、chat 套只读删） | 成立 | 主包:1,954,401/1,682,053 | 无 |
| D25 | —（盲区） | **新增存储键**：`zcode-web-remote-device-names`、`zcode-web-remote-last-device-sid`、`zcode-web-remote-last-device-mid`（localStorage，`Tar` 包装 主包:6,960,139） | **新增** | 主包:6,921,7xx/6,921,8xx/6,922,1xx | 设备记忆三键 |
| D26 | 10§3.3 | sessionStorage `oauth_pending_nonce`/`oauth_pending_provider` | **已变** | 主包:6,866,090 改为 **`oauth_pending_logins`**（结构化 {nonce,provider,…}，上限 `Vnr=5`）；旧两键 0 命中 | 10 篇该行作废 |
| D27 | 10§3.2 | oauth:zai/bigmodel:access_token、zcodejwttoken | 成立+新增 | 主包:6,865,9xx：`A9='oauth:active_provider'`、`j9='zcodejwttoken'`、`Inr/Rnr=access_token` 双键；**新增** `oauth:zai:user_info`/`oauth:bigmodel:user_info` | user_info 两键为新 |
| D28 | 10§3.1 | developer-tools 键 ≤1s 生效（storage+focus+1s 轮询） | 成立（消费面仍在） | 主包:6,001,783 区 `jYn` 数组与判定函数 | 无 |
| D29 | 10§4 | E2E 门 `VITE_ZCODE_E2E_STORE_BRIDGE==='1'` 恒假（无定义点） | 成立 | 主包:246,558/2,536,220/2,645,423 全为读取（`f={dev:d.DEV,e2e:d.VITE_ZCODE_E2E_STORE_BRIDGE==='1'}`）；全快照无定义 | 同 10 篇结论 |
| D30 | 10§4.2 | 8 个 E2E 钩子名单 | 成立（偏移迁移） | 主包:1,699,358/1,237,875/2,330,426/4,670,474/246,672/1,731,974/5,721,897/3,235,618 | 无 |
| D31 | 10§1/锚点审计#55 | window.zcode?.log 仅 OFP 3 处；页面从不写 `window.zcode=`；`__ZCODE_RENDERER_DISABLE_LOGGING__` 总闸 | 成立 | OFP:711/792/1,153、480；主包 `window.zcode=` 0 命中 | 同锚点审计（消失判定亦对：/remote/v4 无生命周期日志沉降点） |
| D32 | 10§2 | CustomEvent 13 个 zcode* 名、壳零监听 | 基本成立 | 主包 15 处 `new CustomEvent(`：auth-ready @2,642,320、lang-change @2,644,059、rewards-context @src:61,762、add-whiteboard/settings-intent/workspace-file-drag-state/add-workspace-file-to-chat/code-comment-remove… 等变量名可解 | 3.17.1 计数口径 15 处（含 radix menu.itemSelect/tooltip.open 非壳面）；"壳零监听"结论不变 |
| D33 | 28§4.1 | `/remote/v4/latest` 字面量 0 命中 | 成立 | 主包 grep=0 | 无 |
| D34 | 10§5#8/28§4.1 | `/web-remote` 是登录页分支 | **已变（消失）** | 主包 `/web-remote` 0 命中；登录改为页内视图（`war()` @6,960,1xx，title='ZCode - Web Remote Control Sign In'） | 10 篇该行作废；新登录流见 A10 |
| D35 | 28§4.1/10§6 | title 三串（Remote Control ×N / Sign In / Web + Server） | 成立 | 主包:6,958,654（Sign In）、6,958,654/6,959,864/6,960,628（Remote Control）、6,981,251（Web + Server） | "一行 title 判落错分支"护栏仍可用 |
| D36 | WEBVIEW-SHELL§2.4 | 整页加载三类来源（版本闸门/登录回调/用户点击）；location.reload 4 处；reloadPage 死参数 | 成立 | 主包 location.replace 4 处：6,873,105（OAuth helper）/6,958,859+6,959,914（登录 onSuccess/onRetry）/6,969,364（闸门）；location.reload 4 处；`reloadPage` 唯一命中 @6,973,350 | 无 |
| D37 | 锚点审计#53 | `/remote/v4` 挂载门/闸门双判据 + P9 集合 | 成立 | 主包:6,926,349/6,969,157；`P9`+`Jnr` @6,869,1xx-2xx（`/remote/v4`,`/remote/v4/`,+`/__test__/` 等） | 补充：`P9` 现含测试路径 |
| D38 | 锚点审计#54 | URL 参数白名单 `['app_version','sid','mid','name']` | 成立 | 主包:6,869,357 `Xnr=new Set([…])` | 无 |
| D39 | —（盲区） | 新入口链 `Iar()`：OAuth 回调检测（`/remote/v4`+`state`+`code|authCode|error`）→ JWT 登录 → 设备选择 → connect；`Cir()` 判据 | **新增** | 主包:6,919,5xx `Cir`、6,924,6xx `Iar`/`start()` | 锚点审计完全未覆盖该前置链 |
| D40 | —（盲区） | `Dar()`/`/api/server-info`：桌面形态探测 initialWorkspaceAbsPath（非 /remote/v4 壳路径） | **新增** | 主包:6,961,1xx | 与壳无关，记录备查 |
| D41 | 23§2/13 | 标题回退 taskList.newThread（readSession 失败静默） | 成立（消费面仍在） | i18n `新建任务` 11 处 @14,263 起 | 无 |
| D42 | 22§4.2/23§4 | RPC 信标 subscribeConversationV4/conversationRowsRangeV4/unsubscribe | 成立 | 主包:269,403/272,928/1,903,964 区 | 无 |
| D43 | 锚点审计#60–62 | onDynamicSessionsIndexFrame、subscribeSessionsIndexV4 三件套 | 成立 | 主包:2,076,335/2,075,233+2,075,498+2,076,221 | 无 |
| D44 | 16§6 | onDynamicConversationFrame/onDynamicControllerFrame/onDynamicConversationTelemetryFact | 成立 | 主包:275,725/1,856,864/1,254,874 | 无 |
| D45 | 16§6 | 懒握手+记忆化（WeakMap/pu），失败删缓存重试 | 成立 | 主包:248,1xx（`pu.set/catch 删`） | 无 |
| D46 | 16§6 | CAS 命令必须带 baseRevision；row target 带 baseLogEpoch | 成立 | 主包:247,1xx `fu(e)`（同构抛错文案） | 无 |
| D47 | 16§6/15 | fault.*（23）与 proto.*（7）错误码 | 成立 | 主包：`fault.subscription.notOwned`×4、`fault.connection.handshakeUnavailable`、`proto.frameTooLarge/payloadTooLarge/staleLogEpoch/independentPlanUnsupported` 均 1+ 命中 | 无 |
| D48 | 15§（P 预算估算器） | `ln(e)`→{cliNdjsonBytes,channelSocketBytes,mobileRelayBytes,maxBytes} | **已变（消失）** | 主包三词 0 命中 | 15 篇该条作废 |
| D49 | 22§2.3/28 | `catalogTree` chunk 存在并消费 theme-surface | **已变（消失）** | 主包/全快照 `catalogTree` 0 命中 | 22 篇该行作废 |
| D50 | 10§3.2 | 其余键抽验（zcode-automations-page-tab、zcode:start-plan-reminder、zcode-task-snapshot-cache:v1、zcode-chat-prompt-history:、zcode:coding-plan:report-context、zcode-interface-mode、zcode-performance-mode、zcode:global-notice:v1、zcode-workspace-expansion） | 成立 | 主包各 1–4 命中（grep=1 等） | 无 |
| D51 | 13§6/锚点审计#59 | 通道枚举含 `zcode-agent` | 成立 | src:29,238/31,644 + 主包:1,903,964 | 无 |

### E. 【锚点审计】77 条逐行重验（差异行详注，其余给判定与证据）

> 口径：偏移以本文实测为准；「=审计」表示与锚点审计给的偏移一致。

| # | 锚点 | 判定 | 证据（字节偏移） | 与审计差异 |
|---|---|---|---|---|
| 1 | data-v4-timeline-scroll | 成立 | 主包:4,592,864（及 4,592,897 连续体） | =审计 |
| 2 | data-v4-timeline-scroll-locked | 成立 | 主包:4,592,897 | =审计 |
| 3 | data-mobile-page="chat"/"home" | 成立 | 主包:5,258,122(home)/5,258,297(chat) | =审计 |
| 4 | section[data-mobile-page=chat] | 成立 | 同上（section 分支） | =审计 |
| 5 | chat-error-banner | 成立 | 主包:998,749（常量定义，字面量@998,754）、使用@3,004,854 | 审计写 998,754/3,004,854，定义行起点差 5B，不影响 |
| 6 | data-v4-running-live-tail | 成立 | 主包:4,595,614 | =审计 |
| 7 | data-zcode-chat-loading-animate | 成立 | CSS:450,665、主包:3,906,412 | =审计 |
| 8 | data-row-count | 成立 | 主包:4,592,988 | =审计 |
| 9 | data-following | 成立 | 主包:4,593,100/4,040,824/5,905,928 | =审计 |
| 10 | data-loading-older | 成立 | 主包:4,593,135 | =审计 |
| 11 | .zcode-boot-loading | 成立 | HTML:9,025 起 8 处 | =审计 |
| 12 | .bg-background-win-alt | 成立 | CSS:114,097、主包:4,403,259 | =审计 |
| 13 | #root | 成立（偏移修正） | HTML:7,627/8,384/8,909/9,220 | **审计 @16,705 错**（该偏移无 #root） |
| 14 | theme-zai-light/dark | 成立 | 主包:79,313/79,390（另 2,641,644/6,957,777 等） | =审计 |
| 15 | data-zcode-browser-theme-surface | 成立 | HTML:4,477 起 7 处、主包:78,419 | =审计（审计列 3 处，实为 7 处，不冲突） |
| 16 | button[aria-haspopup="menu"] | **修正** | 主包 `aria-haspopup` 4 处：399,585(dialog)/425,025(**menu**)/433,881(**menu**)/4,343,202(dialog) | 审计称"4 处全是 dialog、仅 @433,886 是 menu"——**错两处**：menu 字面量有 **2 处**；正确偏移 433,881（audit 的 433,886 差 5B）。运行时仍由 Radix 写出，"取最后一个 menu 钮"的写法碰巧仍对，但二判据建议更必要 |
| 17 | [class*=scrollbar-hide] | 成立 | CSS:58,272、主包:1,853,374/1,878,220/2,847,529/4,548,772 | =审计 |
| 18 | data-zcode-pptx-render-surface | 成立 | CSS:448,875+448,931（主包 0、chunk 内） | =审计 |
| 19 | .xterm-viewport | 成立 | CSS:365,391、主包:5,442,118 | =审计 |
| 20 | role=textbox 默认值 | 成立 | 主包:3,168,089（`role:g=\`textbox\``） | 审计 3,168,090，差 1B |
| 21 | button/[role=button]/[type=submit] | 成立 | 泛型 DOM | — |
| 22 | body * 遍历 | 成立 | 泛型 DOM | — |
| 23 | svg 克隆 | 成立 | 泛型 DOM（lucide 在） | — |
| 24 | .bg-header | 成立 | CSS:123,786、主包:5,258,422 | =审计 |
| 25 | --color-header | 成立 | CSS:16,447/420,712/433,911（另 123,817） | =审计 |
| 26 | 新建任务 | 成立 | i18n:14,263（11 处） | =审计 |
| 27 | 向 ZCode 提问 | 成立 | i18n:335,914（2 处） | =审计 |
| 28 | 继续输入以排队后续修改 | 成立 | i18n:336,139 | =审计 |
| 29 | 提出后续修改要求 | 成立 | i18n:336,079 | =审计 |
| 30 | 初始化任务中 | 成立 | i18n:336,202 | =审计 |
| 31 | 发送 | 成立 | 泛型 i18n | — |
| 32 | 任务会话 | 成立 | i18n:150,922/440,859 | =审计 |
| 33 | zcode-v4-last-session:v1: | 成立 | 主包:4,845,710 | =审计 |
| 34 | zcode-locale-preference | 成立 | 主包:2,715,812、i18n:969,616 | =审计 |
| 35 | zcode-theme | 成立 | 主包:80,586/80,642/2,641,805/6,896,163/6,957,354 | =审计（全部 5 处验证） |
| 36 | zcode-v4-pending-commands:v1 | 成立 | 主包:2,390,768 | =审计 |
| 37 | zcode:auth:jwt-invalid-restart | 成立 | 主包:6,742,184 | =审计 |
| 38 | zcode:coding-plan:embedded | 成立 | 主包:2,641,856 | =审计 |
| 39 | developer-tools/token-debug | 成立 | 主包:6,001,783/6,001,815 | =审计 |
| 40 | zcode-coding-plan-hide-scrollbar | 成立 | 主包:2,643,404 | =审计 |
| 41 | --color-background | 成立 | CSS:14,529、主包:79,075、HTML:7,769 区 | =审计 |
| 42 | --color-border | 成立 | CSS:15,870、主包:91,044 | =审计 |
| 43 | --color-background-win-alt | 成立 | CSS:14,762/114,140/419,117 | 审计 14,763/114,141/419,118，各差 1B |
| 44 | ::-webkit-scrollbar 四项规格 | 成立 | CSS:58,337 区等 | =审计 |
| 45 | scrollbar-gutter:stable | 成立 | CSS:200,730/200,757/200,784 | =审计 |
| 46 | (max-width:767px) 收窄 | 成立 | 主包:3,226,116+3,226,139+3,226,157 | =审计；补充：CSS 内 767px 媒体查询 0 处 |
| 47 | prefers-color-scheme:dark | 成立 | HTML:6,824、CSS:306,258 | =审计 |
| 48 | 合成 online→onRecover | 成立 | kir 主包:6,924,090；白名单 主包:6,971,1xx | =审计 |
| 49 | 合成 pageshow | 成立 | 同上 | =审计 |
| 50 | visibilitychange/freeze/resume/pagehide/pagehide/online 接线 | 成立 | 主包:6,924,090–6,924,3xx | =审计 |
| 51 | document.visibilityState 只读 | 成立 | 泛型（emitDiagnostic 主包:6,913,2xx 等） | — |
| 52 | history.state.zcodeMobilePage | 成立 | 主包:5,256,676/5,256,776/5,256,787/5,257,434/5,258,297 | =审计 |
| 53 | /remote/v4 双判据+P9 | 成立 | 主包:6,926,349/6,969,157、P9/Jnr:6,869,1xx-2xx | =审计 |
| 54 | URL 白名单 Set | 成立 | 主包:6,869,357 | 审计写 6,868,xxx，实测 6,869,3xx |
| 55 | window.zcode?.log 消失 | 成立 | OFP:711/792/1,153；主包 0 | =审计 |
| 56 | rpc-frame/rpc-frame-ack | 成立 | 校验:168,715/169,4xx、parts 内联、主包消费 | =审计（偏移口径一致） |
| 57 | workspace-bridge-open/ready | 成立 | 主包:6,975,134/6,975,486、校验:322,465 | =审计 |
| 58 | workspace-list-request/updated | 成立 | 主包:6,974,257、校验:322,3xx（updated @322,411 result:PT） | =审计 |
| 59 | 通道 zcode-agent | 成立 | src:29,238/31,644、主包:1,903,964 | =审计 |
| 60 | onDynamicSessionsIndexFrame | 成立 | 主包:2,076,335 | =审计 |
| 61 | subscribeSessionsIndexV4 三件套 | 成立 | 主包:2,075,233/2,075,498/2,076,221 | =审计 |
| 62 | subscribeConversationV4/conversationRowsRangeV4 | 成立 | 主包:269,403/272,928 | =审计 |
| 63 | controller/tasks-index | 成立 | parts:48,726（主包 0） | =审计（换落点正确） |
| 64 | clientHello protocolVersion:3/mobileApp | 成立 | parts:10,817/11,286/11,366、主包:248,080 | =审计 |
| 65 | 兜底 appVersion '3.6.5' | 成立（值仍待真机） | 主包 `3.6.5` 0 命中；页面侧 clientHello `appVersion:'unknown'` 主包:248,1xx | 补证据：页面侧无 3.6.5 字面量 |
| 66 | KICKED/错误码枚举 | 成立 | 主包:6,909,421、校验:149,679 | =审计 |
| 67 | mobile-diagnostic | 成立 | 主包:6,969,804/6,970,252、校验:324,922 | =审计 |
| 68 | mobile-view-state-update | 成立 | 主包:6,974,465、校验:323,824 | =审计 |
| 69–77 | workspace-header/side-pane-toggle/terminal-toggle/chat-send-button/v4-composer-send/v4-composer-input/v4-timeline/task-list/task-item/header-slot/WorkspaceSwitcher | 成立 | 主包:1,001,483/996,234/996,212/3,257,448/1,002,345/1,002,251/1,001,813/999,217/999,255/4,594,625/2,350,601（19 处） | =审计（#77 计数 19 正确） |

**77 条合计：成立 75（其中 5 条偏移有 1–5B 口径差）、修正 2（#13、#16）。** 锚点审计"0 死锚点"的总判定维持。

---

## 3. 盲区发现（锚点审计未覆盖、但 3.17.1 已上线的页面机制）

1. **relay V2 传输重写**（最大盲区）：token 鉴权（A4/A7/A8）、`/api/v1/remote/ws2`（A5）、`auth_init` 无 role + 秒级 `client_ts`（A1/A3）、`auth_ack.device_sid` 校验与入站 `terminal_sid/server_ts`（A11）、15s 握手超时（A25）、心跳 5s/ack 15s（A22/A23）、未配对 close 也重连（A14）、suspend 扩态（A15）、rpc-frame-ack 断线豁免（A13）、重放缓冲 32MiB（B21）。
2. **登录/设备选择前置链**（A10/D39）：JWT 登录视图、OAuth 回调参数（state/code/authCode/error）、设备列表 REST（`/api/v1/remote/device`）、`admit` 改写 URL、`device-not-owned/desktop-disconnected` 自动换设备（C8）、新 localStorage 三键（D25）、`oauth_pending_logins`（D26）。
3. **帧族扩容**：workspace-create/task-pin/task-archive 9 臂 schema-only（B27）；telemetry 新增 plugin_discovery 变体（B3）；bridge 能力位 `capabilities.windowProjectDirectory`/`terminalOwnerId`（B28）；bridge-open 新键 `taskHostId/fileTreeTaskId/project{draftId,reuseDraft}`（A33）；`terminalClientSessionId`（A33）；bridge-degraded reason +2（B14）。
4. **任务数据源换轨**：bootstrap/workspace-list 的 `tasks[]` 删除（B6/B7），任务态只剩 sessions-index（`phase` 6 值、`lastOutcome` 新字段、`pendingInteractionSummary` 变对象，B33/B34）；页面开始消费 `windowControlSessionId`（B5）。
5. **mobileViewState 面收敛**：`initialViewState` 在主包 0 消费（C21）；发送侧带 deviceInfo（B11，裁定 49/21 矛盾）。
6. **KICKED 后 UI**：终态文案键（C4）+ V2 新原因 `auth-required`（A34）+ bridge 重建重试梯 pir=[1s,2s,5s]（C9）。
7. **mobile-log 族**：锚点审计已覆盖主体，本轮补全：4+1 个 pull-response 变体与失败臂（B25）、fm 全部限值（B24）。
8. **页面自身重载面**：登录 onSuccess/onRetry 也 `location.replace`（D36 补全）；版本闸门 modal 为命令式 `<dialog>`（C11）。
9. **已消失的旧面**（存档文档未标）：`/web-remote` 登录路由（D34）、HTTP token 通道全族（A9）、`liveStatus`（B34）、`cliNdjson/channelSocket/mobileRelay` 预算估算器（D48）、`catalogTree` chunk（D49）、`mobileHome.*` 与其偏好键（D22）、`oauth_pending_nonce/provider`（D26）。

---

## 4. 对 3.17.1-anchor-audit.md 的修正建议

1. **§4.2 表述订正**："新增 transport 状态 `kicked`" → "3.15.3 已有 `kicked` 终态；3.17.1 新增的是 KICKED 后的一次 2–5s 自动重连（60s 窗）"。同时把 16 篇 §9.1 列为参照，避免"状态集变化"误判。
2. **§4.1 补两句**：modal 是 `mar()` 命令式创建、不在 #root 内（探测 `dialog[aria-labelledby="remote-version-title"]` 时不要限定根）；闸门机制 3.15.3 已存在（24/28/10 篇有载），"3.17.1 新增"应改为"3.17.1 延续"。
3. **#13 修正**：`#root` 在 index.html 的字节偏移是 7,627（另有 8,384/8,909/9,220），非 16,705。
4. **#16 修正**：`aria-haspopup` 字面量主包 4 处中 **2 处是 menu**（425,025、433,881），不是"仅 1 处"；引用偏移 433,881（非 433,886）。结论（运行时写入、建议二判据）不变且更必要。
5. **#54/43/5/20 偏移微调**：6,869,357 / 14,762-419,117 / 998,749 / 3,168,089（1–5B 口径差，建议统一为 grep -bo 实测值）。
6. **§4.3 mobile-log 表补全**：pull-response 有失败臂（success:false）与 gzip 分片变体；`fm` 全限值（maxPullEntries:1e6 等）。
7. **建议增补一节"relay 传输层 3.17.1 变化"**（本文 §2.A/§3.1 的内容），否则壳的原生承载（RelayBridge/NativeRelayClient）按旧协议对接会在握手/心跳/URL 三处直接失配。
8. **§5 P0/P1/P2 落地状态标注**：P0-1/P1-1/P1-2/P2-1 已在 HEAD（0a48ec1）壳侧实施（见 pre-device-review2-*），本文审计结果与之无冲突。

---

## 5. 自查轮次记录

| 轮 | 动作 | 新发现 |
|---|---|---|
| 初验 A | relay 帧序/鉴权/心跳/退避逐词 grep + 上下文解读 | A1–A28 主体（token 化、秒级 ts、5s 心跳、15s 握手超时、close 语义、suspend 扩态） |
| 初验 B | validation 联合枚举/strict 计数/预算常量 | B1–B27（30 字面量、tasks 删除、bridge-degraded 6 值、mobile-log 全族、workspace-create 族） |
| 初验 C | 状态机/KICKED/Pir/mar/isBlocked 门 | C1–C18 |
| 初验 D | 存储/路由/i18n/主题/断点 | D1–D40 主体 |
| R1 | 换关键词+setter 反向 grep（heartbeatIntervalMs=、replayBufferMaxBytes:、location.replace、replaceState、history.state、challenge） | Tar=localStorage、clamp 上限、challenge=OAuth discovery、history.state 单键、yAn 调用点（5 条） |
| R2 | 反向验证 10 条"已变"（假设自己错了再验） | 0（10 条全部维持，其中 4 条补到 schema 级证据） |
| R3 | schema/端点定向扫（Jp/Kp/Up/Ip/Xp、ws2） | relay 顶层 schema（terminal_sid/server_ts）、/api/v1/remote/* 端点、oauth_pending_logins、client_ts 上限=秒（6 条） |
| R4 | mvsu 发送形状/pushState 计数/__zcodeShell 反查 | deviceInfo 仍随帧发送（裁定 49vs21 矛盾）、pushState=1、页面零读壳全局（3 条） |
| R5 | liveStatus/topic 校验器/attachment ops | liveStatus 消失、topic 校验器与附件 ops 存续（2 条） |
| R6 | displayStatus 归属/storage 键抽验 | displayStatus 属 coding-plan 域（1 条） |
| R7 | i18n 家族/锚点偏移复核 | authRequired 新族、#root 偏移错、zcode-theme 5 处全对（3 条） |
| R8 | zHe/textbox/timeline 计数属性 | data-error-code 新属性（1 条） |
| R9/R10 | windowControlSessionId 消费者/Ia 反解 | 页面开始读 windowControlSessionId、Ia=12e4 证实（2 条） |
| R11/R12 | fault/proto/预算估算器/triggerStatus | cliNdjson/channelSocket/mobileRelay 估算器消失（1 条） |
| R13 | catalogTree/wss 全扫 | catalogTree 消失（1 条） |
| R14/R15 | kir 接线复核/wss/ws2 全 chunk 终扫 | **0** |
| 收敛判定 | R14、R15 连续两轮零新发现 | 收尾 |

---

## 6. 复现（全部只读）

```bash
cd E:/Zcode_android/Reference_web/3.17.1/assets
# V2 传输与端点
node -e "const b=require('fs').readFileSync('validation-B0HfQ5gr.js');console.log(b.subarray(149300,151300).toString())"
node -e "const b=require('fs').readFileSync('validation-B0HfQ5gr.js');console.log(b.subarray(125600,127400).toString())"   # ws2/URL 构建
node -e "const b=require('fs').readFileSync('index-Dwhx77cK.js');console.log(b.subarray(6902000,6926000).toString())"      # relay 客户端全量
# 帧族与预算
node -e "const b=require('fs').readFileSync('validation-B0HfQ5gr.js');console.log(b.subarray(318600,325300).toString())"
node -e "const b=require('fs').readFileSync('index-Dwhx77cK.js');console.log(b.subarray(6849600,6850000).toString())"      # D9 预算
# 登录/设备流与闸门
node -e "const b=require('fs').readFileSync('index-Dwhx77cK.js');console.log(b.subarray(6919400,6926000).toString())"
node -e "const b=require('fs').readFileSync('index-Dwhx77cK.js');console.log(b.subarray(6954900,6956500).toString())"      # mar modal
# 77 锚点偏移复核
grep -o -b "data-v4-timeline-scroll" index-Dwhx77cK.js
grep -o -b "aria-haspopup" index-Dwhx77cK.js
```

## 7. 未复核 / 无法判定

| # | 项 | 说明 |
|---|---|---|
| 1 | 桌面端是否实际下发非空 `desktopAppVersion` | 静态快照只能证明"非空即触发"（同锚点审计 §6.1）；壳侧已在 learn 后拼 `app_version`（pre-device-review2），风险已被壳消化 |
| 2 | `auth-required`/`device-not-owned` 是否会进 `rS` 11 值 | 二者目前是 V2 传输层/视图层原因码，不入 `OT`；桌面端归档侧未读 |
| 3 | workspace-create/task-pin/task-archive 的移动端应答者 | schema-only，主包无消费；桌面端行为未在本次范围 |
| 4 | O2（D21）重复帧/缺口判据在 3.17.1 是否逐字同 3.15.3 | parts 归约/去重段未逐行比对（超锚点口径，属 18 篇范围） |
| 5 | 心跳 5s 的对端（relay 服务端）容忍窗 | 静态不可知，需抓帧 |
