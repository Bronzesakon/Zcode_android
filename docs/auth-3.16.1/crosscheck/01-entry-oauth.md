# A 路交叉验证 · 入口/门控 + OAuth（3.16.1）

- 审计方式：**独立重读** `Reference_web/3.16.1/`，不信任 Round 1。未执行快照 JS、未联网、未用真机。
- 只读：未修改任何源码、未修改 Round 1 报告。
- 源指纹已复核（全部与 `docs/auth-3.16.1/README.md` 一致）：

| 文件 | 大小 | SHA-256（前 16） |
|---|---:|---|
| `index.html` | 18,357 | `c4c7c7ff87bfb19a` |
| `assets/index-DbEHT6Gg.js` | 6,836,503 | `dc0d8128b394d624` |
| `assets/src-uVqBd_Eq.js` | 70,714 | `93813e7e2b9ff436` |
| `assets/validation-BBf1FA1a.js` | 331,153 | `7686853747e58b52` |

- 坐标约定：`index-DbEHT6Gg.js:NNN` = 该文件内 **UTF-8 字节偏移**（`Buffer.indexOf(Buffer.from(anchor,'utf8'))`）。本报告的每个偏移都亲自复算过，不是抄 Round 1。
- 证据分级前缀：**[W]** = 网页 3.16.1 快照；**[D]** = 桌面端 `app.asar`（实测 **3.16.3**，见 §5）；**[K]** = 本仓 Kotlin；**[R]** = 本仓 Rust。

### 0.1 审计快照与并发变更（必读）

我在 `16:01` 读入的 Round 1 是：`01` 53,272 B、`02` 44,055 B（mtime 15:54）。审计过程中这两个文件被**并发覆写**（审计结束时 `01` 58,632 B / 16:26、`02` 50,958 B / 16:12）。因此：

- 本报告的 01/02 裁决针对的是 **15:54 那一版**；
- 对 `02` 的 4 条 `CONTRADICTED`，我在收尾时逐条比对过**当前版**，结论如下：
  - **C13（nonce 文案）**：当前版已改正（现写 `Secure random generator is unavailable`）→ 该条只对旧版成立，**已在当前版消解**；
  - **C12（回跳后进设备列表）**：当前版已无此句 → **已消解**；
  - **C4 的 02 半边（桌面 3.16.1 同发布）**：当前版已删除全部 asar 证据并显式声明不复核 → **已消解**（当前版比旧版更保守）；
  - **C11（回调 URL 漏连接参数）**：**当前版仍存在**（`round1/02` 现第 23 行与第 96 行仍写 `/remote/v4?code=…&state=…`），且与它自己的第 26 行「redirect_uri 会复制 sid/mid/name/app_version」自相矛盾 → **仍成立**。
- 对 `01`：我裁决的每一处（line 4 的「3.16.1 同发布」、`last-device` 置空、`mid` 独立成组、`meta` 同名回落、D8/D10/D20、§5.4 版本表）在当前版**逐条仍在**，裁决不变。

---

## 1. 裁决表

### 1.1 报告 01（入口/门控）

| # | 01 的结论 | 裁决 | 我的坐标 + 原文 |
|---|---|---|---|
| 01-1 | 入口 HTML 资源写死 `/remote/v4/3.16.1/assets/…`，版本→assets 映射在服务端 | **CONFIRMED** | [W] `index.html:10646` `<script type="module" crossorigin src="/remote/v4/3.16.1/assets/index-DbEHT6Gg.js">`；全文件 60 处引用且前缀唯一（`/remote/v4/3.16.1/`），无 `VERSION` 占位符；`_manifest.json` `entryUrl=https://zcode.z.ai/remote/v4?app_version=3.16.1`、`assetPrefix=/remote/v4/3.16.1/`、`deploymentVersion=3.16.1` |
| 01-2 | 客户端只做 `app_version` 与 `desktopAppVersion` 字符串比较后整页 replace | **CONFIRMED** | [W] `index-DbEHT6Gg.js:6771185`（`Q9n`）`let s=a.desktopAppVersion?.trim();if(s){let t=new URL(e.getUrl());if(t.pathname.replace(/\/+$/,``)===`/remote/v4`&&t.searchParams.get(`app_version`)!==s){t.searchParams.set(`app_version`,s),r=t.toString(),e.isPageReady()?e.onMismatch(s):o();return}}`；[W] `:6818946` gate `h=window.location.pathname.replace(/\/+$/,``)===`/remote/v4`` |
| 01-3 | `hash`/`t` 任一存在即 `null` → `invalid-mobile-connection`（硬拒绝） | **CONFIRMED** | [W] `src-uVqBd_Eq.js:51830` `function Ja(e){if(e.has(`hash`)||e.has(`t`))return null;…}`；[W] `index-DbEHT6Gg.js:6765795` `let t=mr(e.searchParams);if(!t){this.options.onView({kind:`failure`,failure:{reason:`invalid-mobile-connection`}});return}`；文案 [W] `:6775352` `invalid-mobile-connection":{tone:`warning`,badge:`校验失败`,title:`手机连接已失效`…` |
| 01-4 | 只认 `sid/mid/name/app_version`；`trim()` 空=不存在；重复首个胜；无格式校验 | **CONFIRMED** | [W] `src-uVqBd_Eq.js:51830`（四键枚举）+ `:51431` `function Wa(e,t){return e.get(t)?.trim()||void 0}`。`URLSearchParams.get` 语义即首个胜；`has()` 不看值，故 `?hash=` 空值也拒 |
| 01-5 | 门控顺序 callback→login→params→sid→connect；无 `sid` 是设备列表 | **CONFIRMED** | [W] `index-DbEHT6Gg.js:6765795` `async start(){this.reset();let e=new URL(this.options.getHref());if(H9n(e)){…callback…}if(!this.options.auth.getZCodeJwtToken()){…login…}let t=mr(e.searchParams);if(!t){…failure…}if(!t.deviceSid){await this.openDevices(!0);return}this.connect({...t,deviceSid:t.deviceSid})}` |
| 01-6 | 登录态 = provider+jwt+access_token+user_info 齐全 **且** JWT 未过期；过期 `clearAll()` | **CONFIRMED** | [W] `index-DbEHT6Gg.js:6713613` `if(!n\|\|!P9(t)\|\|!P9(r)\|\|!P9(i))return this.hasAnyStoredCredential()&&this.clearAll(),{status:`signed-out`};if(Zi(t,this.now()).kind===`expired`)return this.clearAll(),{status:`reauthentication-required`,reason:`jwt-expired`}` |
| 01-7 | 回调「**成功后**」清 pending | **CONTRADICTED** | 见 §2-C1：pending 在 `exchangeToken` **之前**清，交换失败也已清 |
| 01-8 | `w7n` 在 redirect 的 pathname 属于 `/remote/v4` 时复制 `[app_version,sid,mid,name]` | **CONFIRMED**（前提需补全） | [W] `index-DbEHT6Gg.js:6717288` `function w7n(e,t,n={}){let r=L9(e),i=L9(t);return!r\|\|!F9.has(r.pathname)\|\|!i\|\|!u7n.has(i.pathname)\|\|!C7n(t,n)?e:(r.search=S7n(i.searchParams).toString(),r.hash=``,r.toString())}`；`p7n` 顺序 [W] `:6715765` `p7n=new Set([`app_version`,`sid`,`mid`,`name`])` |
| 01-9 | localStorage 6 键；`expires_in` 只进内存；过期只靠 JWT `exp`（30s 余量）；无 refresh | **CONFIRMED** | [W] `:6712100` `j9=`oauth:active_provider``、`:6712127` `M9=`zcodejwttoken``；`:6712822` `saveTokenSet(e,t){let n=…accessToken,r=e.zcodeJwtToken;this.localStorage.setItem(N9(t).accessToken,n),r?…setItem(M9,r):…removeItem(M9)}`（**只写 accessToken 与 jwt，无 expiresAt**）；[W] `src-uVqBd_Eq.js:31023` `t+Math.max(0,n)>=l?{kind:`expired`,expiresAt:l}`（n 默认 3e4） |
| 01-10 | 回调只认 pathname `/remote/v4`（不校验 host）；`authCode` 覆盖 `code` | **CONFIRMED** | [W] `:6765087` `function H9n(e){return[`/remote/v4`,`/remote/v4/`].includes(e.pathname)&&e.searchParams.has(`state`)&&(e.searchParams.has(`code`)||e.searchParams.has(`authCode`)||e.searchParams.has(`error`))}`；`parseCallbackParams` [W] `:6711375` `…t.searchParams.get(`code`)?{code:…}:{},…t.searchParams.get(`authCode`)?{code:…}:{}`（后者覆盖） |
| 01-11 | 域名常量写死；provider 只由登录页或 `oauth:active_provider` 决定；链接无 provider | **CONFIRMED** | [W] `validation-BBf1FA1a.js:123471` `var Sd=`https://zcode.z.ai`,Cd=`https://zcode.chatglm.site`,wd=`https://bigmodel.cn`,Td=`https://dev.bigmodel.cn`,Ed=`3.4.0``；`Ja` 四键中无 provider；[W] `:6712100` + `N9(e)` 决定读哪组键 |
| 01-12 | §5.4 用 `app_version → remoteUrl(v3/v4)` 描述入口版本选择 | **CONTRADICTED** | 见 §2-C2：`remoteUrl` 在 web 主包 **0 命中**，`Vd` 只被用来取 `webRemoteCallbackUrl` |
| 01-13 | §6.3「`auth_init` 的 meta，Rust 侧同名回落一致（CONFIRMED）」 | **CONTRADICTED** | 见 §2-C3：`name` 不同（`zemote` vs `mobile-browser`），Android 的 `platform` 也不同 |
| 01-14 | 辅助源桌面端是「3.16.1 同发布」 | **CONTRADICTED** | 见 §2-C4：实测 3.16.3 |
| 01-15 | §4.6 last-device 键「只在 chooseOtherDevice 之后的列表流程里被消费并置空」 | **CONTRADICTED** | 见 §2-C5 |
| 01-16 | §3 表 `mid` 缺失「设备按 `device_sid` 独立成组」 | **CONTRADICTED** | 见 §2-C6 |
| 01-17 | §1.6「visibilitychange → visible 还会触发设备列表刷新，token 变了整体重启」 | **CONTRADICTED**（范围） | 见 §2-C7 |
| 01-18 | D8「Kotlin 不读取、不透传 `app_version`」 | **CONTRADICTED** | 见 §2-C8 |
| 01-19 | D10「Kotlin 只有 ws2」 | **CONTRADICTED** | 见 §2-C9 |
| 01-20 | D20「Kotlin `isCallback` 与 Web 一致（CONFIRMED）」 | **CONTRADICTED** | 见 §2-C10（02 的 D11 才是对的） |
| 01-21 | D1/D2/D3/D4/D5/D6/D7/D9/D11/D12/D13/D14/D15/D16/D17/D18/D19 | **CONFIRMED** | 逐条复核见 §1.3 |
| 01-22 | sessionStorage `zcode:web-remote-control:bootstrap-shell` 是「跨 workspace 首屏快照」 | **UNVERIFIED** | [W] `index.html:4011` `const BOOTSTRAP_SNAPSHOT_KEY = "zcode:web-remote-control:bootstrap-shell";` + `:4073` 读后 `removeItem`。**全部 assets 内 0 命中该键**（`grep -rl` 无结果）→ 写入方不在快照里，用途无法证实 |
| 01-23 | §2.2 `pair_status = waiting_terminal` 是线上取值 | **UNVERIFIED**（wire 上不成立） | [W] `src-uVqBd_Eq.js:52755` 附近 `eo=n([`waiting`,`matched`])`；[W] `index-DbEHT6Gg.js:6755467` `cee(e)===`waiting_terminal`` 是内部归一后的判断，`cee` 在本包内被压缩名遮蔽，无法定位其映射 → 只能证明「内部状态」存在，不能证明 wire 值 |
| 01-24 | §4.5 bfcache `pageshow` 不重新门控，只恢复传输 | **CONFIRMED** | [W] `:6769319` `function q9n({documentTarget:e,windowTarget:t,onSuspend:n,onRecover:r})…c=()=>o(`pageshow`)`；装配 [W] `:6830522` `let U=q9n({documentTarget:document,windowTarget:window,onSuspend:()=>{ee.suspend(),te.suspend()},onRecover:e=>{B(e)}})`。注：01 写的函数名 `recoverInBackground()` 未证实，实际是 `B(e)` |
| 01-25 | §4.4 12 个 reason 表 + `rer` 把 `auth-required` 降级为 `relay-unavailable` | **CONFIRMED** | [W] `:6773150` `ner={"zh-CN":{"device-not-owned":…`；`:6781931` `function rer(e,t){let n=e.reason===`auth-required`?`relay-unavailable`:e.reason;return ner[t][n]}` |
| 01-26 | 带版本前缀的路径（`/remote/v4/3.16.1`）不算远控入口/回调 | **CONFIRMED** | [W] `:6811483` `cr(window.location.pathname,`/remote/v4`)`；`H9n` 只认两值 `:6765087` |
| 01-27 | §5.1 域名常量表（含 `Cd/Td`） | **CONFIRMED** | [W] `validation-BBf1FA1a.js:123471`（同上原文） |

### 1.2 报告 02（OAuth）

| # | 02 的结论 | 裁决 | 我的坐标 + 原文 |
|---|---|---|---|
| 02-1 | 两个 provider，无平台一致性校验；真正拦截点是 `data.<provider>.access_token` | **CONFIRMED** | [W] `:6710263` `let i=(n===`bigmodel`?e.data?.bigmodel?.access_token:e.data?.zai?.access_token)?.trim();if(!i)throw Error(`Token exchange response missing data.${n}.access_token`)` |
| 02-2 | 授权 URL 参数顺序固定：zai `redirect_uri→response_type→client_id→state`；bigmodel `redirect→appId→state` | **CONFIRMED** | [W] `:6710973` `if(e.provider===`bigmodel`){let n=new URLSearchParams({redirect:t,appId:this.config.bigmodelAppId,state:e.state});…}let n=new URLSearchParams({redirect_uri:t,response_type:`code`,client_id:this.config.clientId,state:e.state})` |
| 02-3 | `state` = 无签名 base64url(JSON{nonce, app_return_to, return_to?})；nonce 在 sessionStorage；全等比对 | **CONFIRMED** | [W] `:6716473` `function v7n(e){return g7n(JSON.stringify(e))}`；`:6716519` `y7n`；`:6720237` `let i=this.repo.loadPendingNonce();if(n.nonce!==i)throw Error(`OAuth CSRF 检测失败`)`；`:6715107` `savePendingRedirectUri(e,t){this.sessionStorage.setItem(a7n,JSON.stringify({nonce:e,redirectUri:t}))}` |
| 02-4 | 回调只认路径、不校验 host；成功/重试都 `location.replace(appReturnTo ?? '/remote/v4')` | **CONFIRMED** | [W] `:6803876` `function Ber(){…onSuccess:({appReturnTo:e})=>{window.location.replace(e??`/remote/v4`)},onRetry:()=>{window.location.replace(e??`/remote/v4`)}}` |
| 02-5 | 唯一一次服务端交换：POST `/api/v1/oauth/token`，仅 `Content-Type`，body 4 字段；响应 `code===0`+`data.token`+`data.<p>.access_token` | **CONFIRMED** | [W] `:6711731` `fetch(this.config.tokenUrl,{method:`POST`,headers:{"Content-Type":`application/json`},body:JSON.stringify({provider:t,code:e.code,redirect_uri:e.redirectUri??this.config.redirectUri,state:e.state})})`；`:6718170` `tokenUrl:`/api/v1/oauth/token``；`:6710263` `if(e.code!==0)throw…` |
| 02-6 | 凭据全明文 6 键；`expires_in` 只内存；只靠 JWT `exp`；无 refresh_token | **CONFIRMED** | [W] `:6712822`（saveTokenSet 不含 expiresAt）、`:6713030` `saveUserInfo(e,t){let n=JSON.stringify(e);…}`、`:6713116` `setActiveProvider`；`refresh_token` 在包内仅 1 处 [W] `:97058`，位于通用 OIDC schema（`access_token:K(),id_token:…,refresh_token:K().optional()`），**与 zcode 登录无关** |
| 02-7 | 登录后不立刻换 terminal token；连 relay 时才换；**每次重连都重新换** | **CONFIRMED** | [W] `:6768719` `getToken:e=>{let t=this.options.auth.getZCodeJwtToken();return t?this.options.token(t,e):Promise.reject(new In(`auth-required`))}`；[W] `:6750704` 每次开 socket 都 `let n=await this.options.getToken(t.signal)` 再 `r.searchParams.set(`token`,n)` |
| 02-8 | §2 [6]「回调整页落到 `https://zcode.z.ai/remote/v4?code=…&state=…`」 | **CONTRADICTED**（当前版仍在） | 见 §2-C11 |
| 02-9 | §2 [10]「回跳后…进入设备列表」 | **CONTRADICTED**（仅旧版；当前版已删此句） | 见 §2-C12 |
| 02-10 | §4.1「校验通过后立即 `clearPending*`（用后即焚）」 | **CONFIRMED**（顺序含义见 §2-C1） | [W] `:6720908` `this.repo.clearPendingNonce(),this.repo.clearPendingProvider(),this.repo.clearPendingRedirectUri();` 紧接 `await this.provider.exchangeToken(...)` |
| 02-11 | §4.3「缺 `data.user` 不报错，回落 `{id:'unknown',…}`」 | **CONFIRMED** | [W] `:6710263` `let a=e.data?.user&&A9(e.data.user)?e.data.user:{id:`unknown`,username:`user`,displayName:`User`}`；[W] `:6709691` `A9` 对该回落对象返回非 null |
| 02-12 | §5.1「没有 `storage` 事件监听，多标签页不同步」 | **CONFIRMED**（限定鉴权路径） | 包内确有 1 处 `window.addEventListener(`storage`,…)` [W] `:2060265`，但属项目排序 UI，与凭据无关；凭据侧无监听 |
| 02-13 | §5.2 桌面端 8 键 / `refresh_token` / `zcode://oauth/callback` / CLI 轮询 | **CONFIRMED as [D]**，版本标注错 | [D] `out/host/index.js` `var dbe="zcode://oauth/callback"`；[D] `redirectUri:"zcode://oauth/callback"`（Z.ai 与 BigModel 两处）。但这是 **3.16.3** 证据，不是 3.16.1（§5） |
| 02-14 | D1 `redirect_uri` 白名单 `UNVERIFIED` | **CONFIRMED as 判定**（源码不可证） | 见 §7-Q1；只能给出 SPECULATION：web 把 `sid/mid/name/app_version` 拼进 `redirect_uri`（[W] `:6717288`）却仍能登录，说明服务端至少不按完整串精确匹配 |
| 02-15 | D2 Kotlin 把 `state.app_return_to` 填成 `redirectUri` | **CONFIRMED** | [K] `OAuth.kt:143` `val state = encodeState(nonce, redirectUri)`；[W] 对照 `:6719738` `r=e.appReturnTo??this.runtime.getCurrentHref()` |
| 02-16 | D8 网页会把裸 base64 头像补 `data:image/png;base64,` | **CONFIRMED** | [W] `:6709477` `function Y5n(e){…/^[A-Za-z0-9+/]+={0,2}$/.test(t)&&t.length>=16?`data:image/png;base64,${t}`:t}` |
| 02-17 | D9 Kotlin 把 `expires_in` 当硬过期门 | **CONFIRMED** | [K] `AuthStore.kt:94` `if (stored.expiresAt != null && clock() >= stored.expiresAt) return null`；[W] `expiresAt` 从不落盘（`:6712822`） |
| 02-18 | D10 Kotlin 无 JWT `exp` 校验 | **CONFIRMED** | [K] `AuthStore.kt:92-100` 只读 `expiresAt`；[W] `src:31023` `xr` 解 `exp` |
| 02-19 | D11 回调 host 校验：Android 更严 | **CONFIRMED** | [K] `OAuth.kt:170-173` `if (!uri.scheme.equals("https",…)) return false;…if (!uri.host.equals(expected.host,…)) return false;if (uri.path.trimEnd('/') != OAuth.CALLBACK_PATH) return false` |
| 02-20 | D13 `code` vs `authCode` 优先级相反 | **CONFIRMED** | [K] `OAuth.kt:195` `val code = params["code"] ?: params["authCode"]`；[W] `:6711375` authCode 覆盖 code |
| 02-21 | D14–D21（错误文案/失败页/非ce 存放/URL 清理/401） | **CONFIRMED** | 文案 [W] `:6705721` `callbackErrorTitle:`登录失败`,callbackErrorDescription:`授权流程未完成，请重新登录。`,retryAction:`重新登录``（en-US 在 `:6706191`）；401 [W] `:6747754` `if(i.status===401)throw new In(`auth-required`)` + [W] `:6767441` `if(r instanceof In&&r.reason===`auth-required`){await this.switchAccount();return}` |
| 02-22 | D22–D32（存储/刷新/生命周期/换 token 时机/CLI 流） | **CONFIRMED** | [K] `AuthStore.kt:30-39/92-129`、`LoginViewModel.kt:55-131`；[W] `:6714525` `clearAll(){…removeItem(j9),…M9,…$5n,…e7n,…t7n,…n7n}` |
| 02-23 | nonce 兜底抛错文案 `random generator is unavailable` | **CONTRADICTED**（仅旧版；当前版已改正） | 实际 [W] `:6719045` 附近 `throw Error(`Secure random generator is unavailable`)`。当前版 `round1/02` 第 258/308/457 行已改为正确文案 → 无需再改 |
| 02-24 | 桌面端「3.16.1 同发布」 | **CONTRADICTED**（仅旧版；当前版已删除该前提） | 见 §2-C4。当前版已移除全部 asar 证据并声明「不作为 3.16.1 网页事实继承」 |

### 1.3 01 的 D1–D20 逐条复核（细节）

| D | 01 的说法 | 裁决 | 依据 |
|---|---|---|---|
| D1 | Web 硬拒 `hash/t`；Rust 接受；Kotlin preflight 放行且 `hasPassHash` 可免登录 | CONFIRMED | [R] `connection_params.rs:464-471`（`pass_hash`/`timestamp` 可选）；[K] `PairingUrl.kt:83-84`、`:139` |
| D2 | 重复参数：Web 首个胜，Rust/Kotlin 后者胜 | CONFIRMED | [R] `connection_params.rs:294-313`（`map.insert` 覆盖）；[K] `PairingUrl.kt:207-209` |
| D3 | scheme：Web 不校验；Rust 仅 https/wss；Kotlin 是超集 | CONFIRMED | [R] `:469-471`；[K] `PairingUrl.kt:48` `setOf("http","https","wss")` |
| D4 | host：Rust 不校验（空 host 也收）；Kotlin 拒空 host | CONFIRMED | [R] `parse` 全程不读 host；[K] `:59` `MISSING_HOST` |
| D5 | Rust 解析 `theme`，Web 远控路由不读，Kotlin 不解析 | CONFIRMED | [R] `:480` `theme: get_param(&uri,"theme")`；[K] `PairingUrl.kt` 无 theme；注：Kotlin 仍会经 `EngineConfig.theme` 覆盖（来自设置），与 URL 无关 |
| D6 | Rust 对 `t` 做数字校验（支持 `+`/`-`/`0x`） | CONFIRMED | [R] `:401-423` `parse_int_like_dart` |
| D7 | Web 缺 `sid` = 设备列表；Rust `parse` 返回 None；Kotlin `MISSING_SID` | CONFIRMED | [R] `:463` `let device_sid = get_param(&uri,"sid")?`；[K] `:63` |
| D8 | 「Web/Rust 一致；Kotlin 缺失」 | **CONTRADICTED**（Kotlin 一栏） | 见 §2-C8 |
| D9 | Web 一律要 JWT；Kotlin `token==null && !hasPassHash` 才要登录 | CONFIRMED | [K] `PairingViewModel.kt:139`、`ChatViewModel.kt:766` |
| D10 | 「Kotlin 只有 ws2」 | **CONTRADICTED** | 见 §2-C9 |
| D11 | Kotlin 无设备列表 / 自动选中 / last-device | CONFIRMED | 全仓 `grep -rn "api/v1/remote/device"` → 0 命中；Kotlin 的 `DeviceStore` 是本地配对记录 |
| D12 | Kotlin `REDIRECT_URI` 常量不带参数；靠内存 `pendingUrl` | CONFIRMED | [K] `OAuth.kt:52`、`PairingViewModel.kt:45` |
| D13 | state 语义等价，持久化位置不同 | CONFIRMED | [K] `OAuth.kt:201-211`；nonce 只在 `OAuthRequest`（内存） |
| D14 | Kotlin 无结构化 reason | CONFIRMED | [K] `PairingUiState`（`rejection/error/needsLogin`） |
| D15 | `name` 仅展示 | CONFIRMED | [K] `PairingUrl.kt:74` |
| D16 | trim 空=不存在 | CONFIRMED | [R] `:558-565`、[K] `:216` |
| D17 | `mid` 用于分组 + `?mid=` | CONFIRMED（分组键见 §2-C6） | [R] `:512-514` |
| D18 | 版本自愈 Kotlin/Rust 均无 | CONFIRMED | 全仓无 `desktopAppVersion` |
| D19 | `device-not-owned` ↔ Kotlin `authRequired` 部分等价 | CONFIRMED（仍属 SPECULATION 的等价性） | [K] `ChatViewModel.kt:774`；[W] `:6756541` `AUTH_FAILED`→`device-not-owned` |
| D20 | 回调判定与 Web 一致 | **CONTRADICTED** | 见 §2-C10 |

---

## 2. CONTRADICTED 纠错

### C1 · 01-7：pending 不是「成功后清」，而是**交换之前**清

**正确结论**：`handleCallback` 的顺序是 `state 解码 → (dev return_to) → error → code → nonce 全等 → loadPendingProvider/RedirectUri → clearPending{Nonce,Provider,RedirectUri} → exchangeToken`。因此 **token 交换失败时 pending 也已消失**，用同一个回调 URL 重试必然落到 `OAuth CSRF 检测失败`（`loadPendingNonce()` 为 null）。用户只能重新发起登录（UI 的「重新登录」正是这么做的）。

**坐标**：[W] `index-DbEHT6Gg.js:6720908`
```
this.repo.clearPendingNonce(),this.repo.clearPendingProvider(),this.repo.clearPendingRedirectUri();let s=o??([`/cn/share/callback`,`/share/callback`].includes(new URL(e).pathname)?this.config.shareRedirectUri:this.config.redirectUri),c=await this.provider.exchangeToken({code:t.code,state:t.state,provider:a,redirectUri:s});
```
02 的 §4.1「校验通过后立即清」措辞是对的；01 的 §4.6「回调成功后立即清」不准确。

### C2 · 01-12：`remoteUrl`(v3/v4) 不是网页入口行为

**正确结论**：`Vd`（导出名 `$n`）确实被主包 import（别名 `a`），但只用于 `k7n` 里取 `webRemoteCallbackUrl` / `webShareCallbackUrl`。字段 `remoteUrl` 在 web 主包 **0 次**引用；`app_version` 在网页端只影响 ① `auth_init.meta.version`、② 版本自愈比较。`v3/v4` 的 semver 判定（`Dd`/`Ed=3.4.0`）是**桌面端/服务端**的语义，不属于网页远控入口。

**坐标**：[W] `index-DbEHT6Gg.js:6717957`
```
function k7n(e={}){…return{authorizeUrl:D7n(…),tokenUrl:`/api/v1/oauth/token`,…,redirectUri:n.webRemoteCallbackUrl,shareRedirectUri:n.webShareCallbackUrl,…}}
```
统计（本文件内字符串命中数）：`remoteUrl` = 0；`webRemoteCallbackUrl` = 1，位于 [W] `:6718393` `redirectUri:n.webRemoteCallbackUrl,shareRedirectUri:n.webShareCallbackUrl`；`relayWsUrl` = 2（`:6821776` `relayWsUrl:Qer()` 装配、`:6750829` transport 读取）。判据函数 [W] `validation-BBf1FA1a.js:125811` `a=Dd(t.appVersion)?`v4`:`v3`;return{…remoteUrl:`${n}/remote/${a}`,webRemoteCallbackUrl:`${n}/remote/v4`,…}`。

### C3 · 01-13：Rust 的 `meta.name` 是 `zemote`，不是 `mobile-browser`

**正确结论**：`auth_init.meta` 三个字段在 Android 上与网页**三个都不同**：

| 字段 | 网页 3.16.1 | Rust（`RelayClient::new`） | Android 实际注入 |
|---|---|---|---|
| `platform` | `"web"` | 宿主传入，`zemote_platform_name(false, Android)` = `"android"` | `"android"`（`EngineConfig.targetPlatform` 默认值） |
| `version` | `appVersion ?? "web"` | `params.app_version ?? "web"`（一致） | `BuildConfig.VERSION_NAME`（**覆盖链接里的值**） |
| `name` | `"mobile-browser"` | `"zemote"` | `Build.MODEL`（**覆盖**） |

**坐标**：
- [R] `rust/crates/protocol/src/device_info.rs:20` `pub const ZEMOTE_APP_NAME: &str = "zemote";`（`:120` 单测 `assert_eq!(ZEMOTE_APP_NAME, "zemote")`）
- [R] `rust/crates/protocol/src/relay_client.rs:349-354` `version: params.app_version.clone().unwrap_or_else(|| "web".to_string()), name: ZEMOTE_APP_NAME.to_string(),`
- [W] `index-DbEHT6Gg.js:6751399` `type:`auth_init`,device_sid:this.options.deviceSid,meta:{platform:`web`,version:this.options.appVersion??`web`,name:`mobile-browser`},client_ts:Math.floor(Date.now()/1e3)`
- [K] `android/.../ui/AppContainer.kt:147-148` `appVersion = BuildConfig.VERSION_NAME, deviceName = android.os.Build.MODEL,`

所以 01 §6.3「meta 与 Rust 同名回落一致」只在 `version` 的 `"web"` 回落上成立。

### C4 · 01-14 / 02-24：桌面端 `app.asar` 是 **3.16.3**

**正确结论**：本机 `app.asar` 的 `package.json.version` = `3.16.3`。Round 1 的 03 报告写对了；01（当前版第 4 行仍是「3.16.1 同发布」）与 02 旧版都错了（02 当前版已删除该前提）。所有 [D] 证据严格属于 3.16.3，不能当作 3.16.1 的行为证明；只能作「服务端/主机契约在 3.16.1→3.16.3 未变」的旁证（而这一点本身也需 diff 才能成立）。

**坐标**：[D] `app.asar` 绝对偏移 `341378799`（`package.json` 条目数据起点 `341377026` + 1773）
```
"version": "3.16.3",
  "zcodeProductFlavor": "production",
```

### C5 · 01-15：last-device 键永不删除；只有内存副本被置空

**正确结论**：
1. `zcode-web-remote-last-device-sid/-mid` 只有三处：定义、`openDevices(true)` 读、`rememberDevice` 写。**没有任何 `removeItem`**（`removeItem(U9n)` 命中数 = -1）。
2. `chooseOtherDevice()` 调 `openDevices(!1)`，参数为 false，**根本不读**这两个键。
3. 真正被「置空」的是视图模型的内存字段 `e.lastSid=null,e.lastMid=null`（首次拉列表后，避免再次自动选中）。
4. `rememberDevice` 不只手动选设备时调用——`connect` 的 `onPaired` 也会调，即**每次配对成功都会刷新 last-device**（[W] `:6768849` `onPaired:()=>{t.aborted||this.rememberDevice(e.deviceSid,e.deviceMid)}`）。

**坐标**：
- 定义 [W] `index-DbEHT6Gg.js:6765283` `U9n=`zcode-web-remote-last-device-sid`,W9n=`zcode-web-remote-last-device-mid``
- 读 [W] `:6766274` `e&&(t=this.options.storage.getItem(U9n),n=this.options.storage.getItem(W9n))`
- 写 [W] `:6768523` `rememberDevice(e,t){try{this.options.storage.setItem(U9n,e),t&&this.options.storage.setItem(W9n,t)}catch{}}`
- 内存置空 [W] `:6767267` `e.lastSid=null,e.lastMid=null,o?this.selectDevice(o):this.options.onView(e.view)`

02 §5.1「不清理」是对的。

### C6 · 01-16：设备分组键是 `device_mid`，缺 `mid` 会塌成同一个 `undefined` 组

**正确结论**：`L9n` 用 `s.get(e.device_mid)` 分组；`mid` 缺失时所有这类连接共享同一个 key=`undefined` 的组，**不会**按 `device_sid` 独立成组。`device_sid` 只在排序 tie-break 里出现。

**坐标**：[W] `index-DbEHT6Gg.js:6763599`
```
function L9n(e,t,n=new Map){…let o=e.toSorted((e,t)=>{…r.compare(e.device_mid,t.device_mid)||r.compare(e.device_sid,t.device_sid)}),s=new Map;for(let e of o){let t=s.get(e.device_mid);if(t||(t={deviceMid:e.device_mid,name:i(e)||void 0,onlineConnections:[]},s.set(e.device_mid,t)),…}
```

### C7 · 01-17：`visibilitychange` 刷新只存在于「设备列表视图」

**正确结论**：`subscribeVisible` 只在 `openDevices()` 里注册并立即销毁（`this.list.unsubscribe`），连接页/配对页没有它。连接页用的是 `q9n`（suspend/recover 传输），不会 `refreshDevices()`，也不会因 token 变化 `start()`。因此「标签页重新可见 → 发现 token 变了 → 整体重启」仅在设备列表停留时成立。

**坐标**：
- 注册 [W] `index-DbEHT6Gg.js:6766274` 之后 `this.list={view:{…},unsubscribe:this.options.subscribeVisible(()=>{this.refreshDevices()}),lastSid:t,lastMid:n}`
- 注入 [W] `index-DbEHT6Gg.js:6805510` `subscribeVisible:e=>{let t=()=>{document.visibilityState===`visible`&&e()};return document.addEventListener(`visibilitychange`,t),()=>document.removeEventListener(`visibilitychange`,t)}`（K9n 装配处，紧跟 `storage:Uer`）

### C8 · 01-18（D8）：Android 会用**自己的**版本号覆盖链接的 `app_version`

**正确结论**：Kotlin 确实不解析链接的 `app_version`，但它把 `BuildConfig.VERSION_NAME` 作为 `config.appVersion` 传给 Rust，而 `parse_config` 的规则是「显式给了就覆盖 URL 解析值」。所以 `auth_init.meta.version` 在 Android 上是**应用版本号**，不是桌面端版本。这一点对「版本自愈」有实际影响：即便将来补自愈，比较对象也会是 Android 版本而非链接版本。

**坐标**：
- [K] `android/.../ui/EnginePort.kt:298-305` `created.start(EngineConfig(url = target, appVersion = appVersion, deviceName = deviceName, theme = theme(), token = terminalToken))`
- [K] `android/.../engine/EngineModels.kt:321` `put("appVersion", appVersion.orNull())`
- [R] `rust/crates/ffi/src/session.rs:475-477` `if let Some(app_version) = optional_string(map, "appVersion")? { params.app_version = Some(app_version); }`（注释：「显式给了就**覆盖** URL 里解析出来的对应字段」）

### C9 · 01-19（D10）：「Kotlin 只有 ws2」不成立

**正确结论**：Kotlin 不做 URL 选择，全部交给 Rust；Rust 按 `auth_token` 是否存在二选一：有 token → `/api/v1/remote/ws2`，无 token → legacy `/ws`。而 Kotlin 在「旧链接（带 `hash`）+ 未登录」时正好传 `token=null`，于是**会走到 `/ws`**。所以 legacy 路径在 Android 上是可达的，不是「只有 ws2」。

**坐标**：[R] `rust/crates/protocol/src/connection_params.rs:493-497`
```
let path = if self.auth_token.is_some() {
    "/api/v1/remote/ws2"
} else {
    "/ws"
};
```
配合 [K] `EnginePort.kt:316-318` `resolveTerminalToken`: `if (jwt == null) return null; if (PairingUrl.hasPassHash(url)) return null;`。

### C10 · 01-20（D20）：Kotlin 回调判定比 Web 严，不是「一致」

**正确结论**：Web 只比 pathname（且允许 `/remote/v4/`）；Kotlin 额外要求 `scheme == https` 且 `host == zcode.z.ai`。两者对真实回调等价，但判定集合不同（Web 是 Kotlin 的超集）。01 写「一致（CONFIRMED）」，02 的 D11「Android 更严」才是准确表述。

**坐标**：[K] `android/.../auth/OAuth.kt:168-177`
```
fun isCallback(url: String, redirectUri: String = OAuthConfig.REDIRECT_URI): Boolean {
    val uri = parse(url) ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val expected = parse(redirectUri) ?: return false
    if (!uri.host.equals(expected.host, ignoreCase = true)) return false
    if (uri.path.trimEnd('/') != OAuthConfig.CALLBACK_PATH) return false
```

### C11 · 02-8：回调 URL **自带**四个连接参数

**正确结论**：`redirect_uri` 由 `w7n(config.redirectUri, appReturnTo)` 生成，会把 `app_version/sid/mid/name` 复制到 redirect 上；授权服务器 302 回的就是这个带参 URL。所以回调页的真实形态是：

```
https://zcode.z.ai/remote/v4?app_version=3.16.1&sid=S1&mid=M1&name=Mac&code=…&state=…
```
（前四个来自 `S7n` 固定顺序，`code/state` 由授权服务器追加。）

02 §2 [6] 写的 `?code=…&state=…` 漏了这四个参数，与它自己 §3.2「redirect_uri 可能带 sid/mid/name/app_version」自相矛盾；01 §1.5 的表述才是对的。

**坐标**：[W] `index-DbEHT6Gg.js:6717288`（同 01-8 原文）+ [W] `:6716909` 区间 `function S7n(e){let t=new URLSearchParams;for(let n of p7n){let r=e.get(n);r!==null&&t.set(n,r)}return t}`

### C12 · 02-9：回跳后是**直接连设备**，不是进设备列表（**当前版已删此句**）

**正确结论**：`app_return_to` 是登录前那一刻的 href（含 `sid`），经 `C7n` 清洗后仍是 `/remote/v4?...&sid=...`。`location.replace` 后重跑 `start()`：JWT 已有 → `Ja()` 解析出 `deviceSid` → **`connect()`**。只有 `app_return_to` 为 null（跨源/路径不合法）时才退到裸 `/remote/v4` → 设备列表。

**坐标**：[W] `index-DbEHT6Gg.js:6803876`（`Ber` 的 `replace(e??`/remote/v4`)`）+ `:6765795`（`if(!t.deviceSid){…openDevices…}this.connect({...t,deviceSid:t.deviceSid})`）

### C13 · 02-23：nonce 兜底错误文案（**当前版已自行改正**）

**正确结论**：文案是 `Secure random generator is unavailable`，不是 `random generator is unavailable`。此条只对 15:54 版成立；当前版 `round1/02` 第 258/308/457 行已是正确文案，无需再改。

**坐标**：[W] `index-DbEHT6Gg.js:6719045` 起
```
function j7n(){if(globalThis.crypto?.randomUUID)return globalThis.crypto.randomUUID();if(!globalThis.crypto?.getRandomValues)throw Error(`Secure random generator is unavailable`);…}
```

---

## 3. MISSING（两份报告都没写、但源码存在）

| # | 缺失的分支/字段 | 坐标 + 原文 | 影响 |
|---|---|---|---|
| M1 | `handleCallback` 里 `redirect_uri` 的**回退分支**：pending 缺失时按回调路径选 `shareRedirectUri` 或 `redirectUri` | [W] `:6720908` `let s=o??([`/cn/share/callback`,`/share/callback`].includes(new URL(e).pathname)?this.config.shareRedirectUri:this.config.redirectUri)` | 解释「为什么 pending 丢了也能换 token」（会退回不带参的基址） |
| M2 | `Z5n` 还有一条 `Token exchange response missing user info` 抛错（当前不可达） | [W] `:6710263` `let o=A9(a);if(!o)throw Error(`Token exchange response missing user info`)` | 说明 user 归一化失败时的兜底顺序 |
| M3 | `A9` 的 snake_case 兜底规则：`displayName = name\|\|email\|\|user_id`，全空且 `id==='unknown'` 才返回 null | [W] `:6709691` `let t=typeof e.user_id==`string`?e.user_id:`unknown`,n=…e.name…,r=…e.email…,i=n\|\|r\|\|t;return!n&&!r&&t===`unknown`?null:{id:t,username:i,displayName:i,…}` | 01 §4.2 只写了驼峰三字段，漏了这条 |
| M4 | `user_info` JSON 解析失败也会 `clearAll()`（不只 signed-out） | [W] `:6713613` 尾部 `catch{}return this.clearAll(),{status:`signed-out`}` | 与「密文坏」是同一后果，但触发点不同 |
| M5 | `saveTokenSet` **只写当前 provider** 的键，不清理另一 provider | [W] `:6712822` `this.localStorage.setItem(N9(t).accessToken,n)` | 切换 provider 后旧键残留；`clearAll` 才全清 |
| M6 | `logout()` 只清 localStorage 6 键，**不动** sessionStorage pending | [W] `:6721850` `async logout(){this.repo.clearAll()}` | 登出后 pending nonce 仍在（但下次登录会覆盖） |
| M7 | `Ber` 的 `onRetry` 也 `replace(appReturnTo)`，重试会丢掉回调 query | [W] `:6803876` `onRetry:()=>{window.location.replace(e??`/remote/v4`)}` | 「重试」实际是回到登录前页面重来 |
| M8 | `w7n` 要求**基址** `redirectUri` 的 pathname ∈ `F9`，否则原样返回基址（即使 appReturnTo 合法） | [W] `:6717288` `!r\|\|!F9.has(r.pathname)\|\|…?e:(…)` | 若把基址改成别的路径，复制逻辑会静默失效 |
| M9 | `C7n` 对 share 路径**只返回 pathname**（丢弃 query） | [W] `:6717014` `if(f7n.test(n.pathname))return n.pathname` | 分享回调后的 URL 一定被剥干净 |
| M10 | 三套路径集合语义不同：`F9`(v4) ⊂ `u7n`(v4+`__test__`) ⊂ `d7n`(v4+share callback) | [W] `:6715549` `F9=new Set([…])，u7n=new Set([...F9,`/remote/__test__`,`/remote/__test__/`])，d7n=new Set([...F9,`/cn/share/callback`,`/share/callback`])` | 02 §4.2 只提 `/remote/v4`，漏了 `__test__` 在 C7n 白名单里 |
| M11 | `auth_init` 的 `meta` schema 极松：`{name?,platform?,version?}`，无长度/取值约束 | [W] `src-uVqBd_Eq.js:52755` 附近 `Qa=_({name:y().optional(),platform:y().optional(),version:y().optional()})` | 说明 meta 只用于展示/协商，不参与鉴权 |
| M12 | `device_sid` 的 schema 约束（`1..128` 字符且 UTF-8 ≤128 字节） | [W] `src-uVqBd_Eq.js:52755` `U=y().min(1).max(128).refine(e=>e.trim().length>0),Za=U.refine(e=>new TextEncoder().encode(e).length<=128)` | 网页端本地不校验，超长 sid 会在 relay 侧失败（01 Q5 仍 UNVERIFIED） |
| M13 | `k7n` 的 `endpointOrigin` 已烘焙，`redirectUri` 恒为 `https://zcode.z.ai/remote/v4` | [W] `:6718566` `R9=k7n({BASE_URL:`/remote/v4/3.16.1/`,…VITE_ZCODE_BASE_URL:`https://zcode.z.ai`,VITE_ZCODE_ENDPOINT_ORIGIN:`https://zcode.z.ai`})` | 说明 02 §3.1 的 `redirectUri` 推导链条成立（`webRemoteCallbackUrl` 用 endpoint origin，不是页面 origin） |
| M14 | `loginOptions:{devReturnTo:A7n(R9)}`，prod 下 `A7n` 返回 `undefined` | [W] `:6718931` `function A7n(e,t=`remote`){return e.devOrigin?`${e.devOrigin}/…`:void 0}` + `:6805696` `loginOptions:{devReturnTo:A7n(R9)}` | 证实 `return_to` 分支在线上不可达 |
| M15 | Android `parseCallback` 的判定顺序与 Web 不同：Kotlin 先校 nonce 再看 `error` | [K] `OAuth.kt:189-196` | 同时带 `error` 且 nonce 不匹配时，Web 报 `OAuth login failed`，Kotlin 报 `OAuth CSRF 校验失败` |
| M16 | 授权流**无 PKCE**（无 `code_challenge`/`code_verifier`） | [W] `:6710973`（四参数）、`:6711731`（四字段） | 与 02 §8.7 的疑问一致，但 02 未把它列为「确认缺失」 |
| M17 | relay 失败 `reason` 字面量 `"auth-failed"` 会触发 Kotlin 清态 | [K] `ChatViewModel.kt:1354` `val needsLogin = f?.reason == "auth-failed"` | 01/02 只写了 `auth-required`；这是第二个触发词 |
| M18 | `ntr` 仅在远控/回调路径设置 `document.documentElement.lang` | [W] `:6830751` `if((Jer()\|\|H9n(new URL(window.location.href)))&&(document.documentElement.lang=G9(navigator.language)),zer(e)){…}` | 分享页/普通页另走 `Ver`/`Ker` 的 lang 设置 |

---

## 4. 旧基线残留清单

| # | 位置 | 残留内容 | 判定 |
|---|---|---|---|
| R1 | `round1/01` §3 参数表 | 把 `hash`/`t` 当作「链接字段」并给出来源「旧链接（≤3.15.x）」 | 结论本身对（存在即拒），但表格框架沿用了旧基线的字段集。3.16.1 桌面端 `qpe` 是「先 set sid，再 **delete hash / delete t**」，链接根本不生成它们。建议改写成「禁止出现的旧基线参数」 |
| R2 | `round1/01` D1/D6/D9/D10 | 把 `hash`/HMAC/legacy `/ws` 作为「当前实现差异」 | 这些是**本仓**的旧基线遗留，不是 3.16.1 规格；报告已标注，但 D10 的结论（「Kotlin 只有 ws2」）与 Rust 事实冲突（§2-C9） |
| R3 | `rust/crates/protocol/src/connection_params.rs:4` | 模块文档仍写 `?sid=...&hash=...&t=...&mid=...&name=...&app_version=...&theme=...` | 实现内残留（非报告）。3.16.1 新链接无 `hash`/`t`；建议注明「旧链接示例」 |
| R4 | `rust/crates/protocol/src/connection_params.rs:624/640/649/658/672/721/729` | 多条单测仍以 `?sid=s&hash=h&t=1` 为基准 | 实现内残留；已补 `parse_new_3161_link_without_hash_and_t`，但默认样例仍是旧链接 |
| R5 | `android/.../pairing/PairingUrl.kt:8`、`:78-84` | 文档与 `hasPassHash` 仍以「legacy 链接可走 HMAC proof」为现状 | 实现内残留，但代码注释已注明「3.16.1 起不再带 hash」；风险点是 `hasPassHash` 让旧链接可绕过登录 |
| R6 | `round1/01` Q12 | 主动把 `hash` 的 HMAC 语义标为「不在本次范围」 | 处理正确，不构成残留 |
| R7 | `round1/02` | 未发现 `hash`/HMAC/`auth_challenge` 残留 | 干净 |

结论：**两份报告没有把 3.15.3 的结论当规格**；旧基线残留在实现侧（R3–R5），不在报告侧。

---

## 5. 网页 / 桌面证据混淆清单

| # | 位置 | 问题 | 纠正 |
|---|---|---|---|
| X1 | `round1/01:4`、`round1/02` 旧版第 3 行（该版已被覆写） | 「桌面端 `app.asar`（3.16.1 同发布）」 | 实测 **3.16.3**（`app.asar:341378799`）。Round 1 的 03 报告是对的；01 当前版第 4 行仍是错的，02 当前版已删掉该前提 |
| X2 | `round1/01` §5.4 / §7.5 | 用 [D] 的 `qpe`、`dE`、`Vd` 说明**网页**入口版本选择 | `remoteUrl`(v3/v4) 在 web 主包 0 命中（§2-C2）。[D] 的 `dE`/`qpe` 只能证明桌面端如何**生成**链接 |
| X3 | `round1/01` §6.3 | 把 Rust `meta` 与 web `meta` 混为一谈，称「同名回落一致」 | `name` 不同（`zemote` vs `mobile-browser`）；Android 的 `platform`/`version`/`name` 三个都被宿主覆盖（§2-C3） |
| X4 | `round1/02` §1.8 / D1 | 用 [D] 的 `zcode://oauth/callback` 推断服务端 `redirect_uri` 白名单「至少含它」 | 这是**服务端行为**，源码不可证 → 必须 `UNVERIFIED`（02 在 §8.1 已标 UNVERIFIED，但 §1.8 的措辞像结论） |
| X5 | `round1/02` §5.2 | 桌面 8 键（含 `refresh_token`、`oauth:login_attribution`）与网页 6 键并列 | 两组是不同介质（`credentials.json` vs `localStorage`）、不同版本（3.16.3 vs 3.16.1）。结论「网页没有 refresh」正确，但桌面那半必须标 [D] |
| X6 | `round1/01` §7.5 / `round1/02` §7.4 | 把 `tools/asar.mjs grep` 的 `@offset` 当**字节偏移** | 该脚本用 `re.exec(s)` 在 **UTF-16 字符串**上取 `m.index`，不是字节偏移。实测 `out/main/chunk-CJROBAMZ.js` 中 `function qpe(` 的 UTF-16 索引 813,494 / 字节偏移 813,558（差 64，因为前面有中文）。01 给的 `813503` 两者都不是 → asar 坐标不可复现，需重算 |
| X7 | `round1/01` §5.1 / §7.4 | 域名常量坐标 `123467` 与我复算的不一致（差 4 字节） | 实测 `var Sd=` 起于 `123471`（`Sd=` 也起于 `123471`，因为 `Sd` 前是 `var `，`var` 在 123467）。即 01 的 `123467` 是 `var` 的起点、不是 `Sd` 的起点。锚点不同而非错误，但报告未说明锚点，复现时会以为对不上 |
| X8 | `round1/01` §2.2 | `pair_status = waiting_terminal` 当作 wire 值 | wire schema 只有 `waiting`/`matched`（`src:52755` 附近 `eo=n([`waiting`,`matched`])`）；`waiting_terminal` 是内部归一值，`cee` 未定位 → 标 UNVERIFIED |

---

## 6. 最终可信结论集（可直接用于实现）

### 6.1 入口 / 门控（[W]）

1. 版本由服务端按 `app_version` 选 HTML；页面内资源前缀 `/remote/v4/<ver>/assets/…` 是构建产物，客户端不改写资源 URL。
2. 客户端唯一的版本动作：bootstrap 拿到 `desktopAppVersion` 后，若 `pathname === '/remote/v4'` 且 `?app_version !== desktopAppVersion`，把 `app_version` **改成桌面版本**并整页 `location.replace`；页面未就绪时立即跳，已就绪时先弹「桌面版本已更新」对话框。
3. 链接参数只有四个：`sid → deviceSid`、`mid → deviceMid`、`name → deviceName`、`app_version → appVersion`；值 `trim()` 后空 = 不存在；重复取第一个；不做任何格式/host/scheme 校验。
4. `hash` 或 `t` **存在即作废**（值是否为空无所谓）→ 失败页 `invalid-mobile-connection`（「校验失败 / 手机连接已失效」）。
5. 门控顺序固定：`callback` → `JWT` → `parse params` → `sid` → `connect`。**无 `sid` 不是错误**，是设备列表（需要登录）。
6. 设备列表：`GET /api/v1/remote/device`（Bearer JWT，15s 超时，401 → 登出回登录页）；自动选中规则 = 上次 `sid` 在线 → 否则按 `mid` 分组且该组只有 1 个在线连接；分组键是 `device_mid`；`last-device-*` 键只写不删。
7. 失败页有 12 个结构化 reason，`auth-required` 会被降级显示成「中转异常」；`device-not-owned` 才有「切换账号」。

### 6.2 登录态（[W]）

8. 已登录 = `active_provider ∈ {zai,bigmodel}` **且** `zcodejwttoken` 非空 **且** `oauth:<p>:access_token` 非空 **且** `oauth:<p>:user_info` 能归一化出用户 **且** JWT `exp` 未过期（30s 余量）。任一不满足 → `clearAll()`（JWT 过期分支无条件清）。
9. 凭据 6 键全明文 localStorage；`expires_in` 只算内存 `expiresAt`，不落盘、不用于拦截；无 refresh_token；JWT 过期只能重登。
10. `logout()` = `clearAll()`（6 键），不动 sessionStorage pending。

### 6.3 OAuth（[W]）

11. 授权 URL：
    - Z.ai：`https://chat.z.ai/api/oauth/authorize?redirect_uri=…&response_type=code&client_id=client_P8X5CMWmlaRO9gyO-KSqtg&state=…`
    - BigModel：`https://bigmodel.cn/login?redirect=…&appId=zcode&state=…`
    - 无 PKCE。
12. `redirect_uri` 基值 `https://zcode.z.ai/remote/v4`；若当前 href 同源且 pathname ∈ `/remote/v4(/),/remote/__test__(/)`，则把 `app_version,sid,mid,name`（此顺序）复制上去并去 fragment。
13. `state` = 无签名 base64url(JSON `{nonce, app_return_to}`)；`nonce = crypto.randomUUID()`；nonce 存 `sessionStorage['oauth_pending_nonce']`，provider 存 `oauth_pending_provider`，带参 redirect 存 `oauth_pending_redirect_uri = {nonce, redirectUri}`。
14. 回调识别：pathname ∈ `/remote/v4`, `/remote/v4/` 且带 `state` 且带 `code|authCode|error`；**不校验 host**；`authCode` 覆盖 `code`。
15. 回调处理顺序（严格）：`parse → state 解码 → dev return_to → error → code → nonce 全等 → 取 pending provider/redirect → 清 pending → 换 token`。**清 pending 在换 token 之前**。
16. 换 token：`POST /api/v1/oauth/token`，`Content-Type: application/json`，无 Authorization，body `{provider, code, redirect_uri, state}`（`redirect_uri` 用 pending 那份带参的）；响应必须 `code===0`、`data.token` 非空、`data.<provider>.access_token` 非空；`data.user` 缺失时回落 `{id:'unknown',username:'user',displayName:'User'}`。
17. 落盘：`zcodejwttoken = data.token`，`oauth:<p>:access_token`，`oauth:<p>:user_info = JSON.stringify(rawUser)`，`oauth:active_provider = p`（只写当前 provider，不清另一组）。
18. 回跳：`location.replace(C7n(app_return_to) ?? '/remote/v4')`；清洗规则 = 同源 + pathname 白名单 + 只保留四个参数（丢弃 fragment）。正常路径下回跳 URL 带 `sid`，因此重跑门控后**直接 connect**，不进设备列表。

### 6.4 本仓现状（[K]/[R]，可直接对照）

19. Rust `meta.name = "zemote"`、`platform = "android"`、`version = 宿主注入的 appVersion（默认 BuildConfig.VERSION_NAME，覆盖链接值）`。
20. Rust `connection_params` 同时保留 token 模式（`/api/v1/remote/ws2`）与 legacy 模式（`/ws`），由 `auth_token` 是否存在决定。
21. Kotlin 侧与网页的真实差异（已复核）：`hash/t` 放行、重复参数后者胜、`theme` 不解析（但会被设置覆盖）、缺 `sid` 直接拒、无设备列表、无版本自愈、`app_return_to` 填 `redirectUri`、`isCallback` 更严、`expires_in` 当硬过期门、无 JWT `exp` 校验、`code` 优先于 `authCode`、回调校验顺序与 Web 不同。

---

## 7. 未解问题

| # | 问题 | 现状 | 为什么不能从源码判定 |
|---|---|---|---|
| Q1 | `redirect_uri` 白名单是否接受 `https://zcode.z.ai/remote/v4`（含任意 query） | **UNVERIFIED** | 授权服务器侧逻辑，快照与 asar 都没有。唯一间接证据：web 自己会把 `sid/mid/name/app_version` 拼进 `redirect_uri` 并成功登录（[W] `:6717288`），说明至少不是完整串精确匹配 → **SPECULATION**，不是结论 |
| Q2 | `data.expires_in` 是 OAuth access_token TTL 还是 JWT TTL | **UNVERIFIED** | 网页端只把它存内存且不拦截（[W] `:6710198` `X5n`），无法反推语义 |
| Q3 | JWT 是否一定带 `exp` | **UNVERIFIED** | [W] `src:31023` `xr` 对缺 `exp` 返回 `unknown` 且**不拦截**，说明服务端可能签发无 `exp` 的 JWT |
| Q4 | 服务端对 `state` 是否校验长度/字符集 | **UNVERIFIED** | 纯服务端行为 |
| Q5 | `cee()` 对 `pair_status` 的归一规则（`waiting_terminal` 从哪来） | **UNVERIFIED** | `cee` 在本包被压缩名遮蔽，无法定位定义（B 路范围） |
| Q6 | `zcode:web-remote-control:bootstrap-shell` 的写入方 | **UNVERIFIED** | 全 assets 0 命中该键，只有 `index.html` 读+删 |
| Q7 | `/remote/v4` 不带 `app_version` 时服务端回哪个版本 | **UNVERIFIED** | 快照只有「给了 3.16.1 就回 3.16.1」这一条记录 |
| Q8 | 桌面 3.16.1 与 3.16.3 在 OAuth/二维码上的差异 | **UNVERIFIED** | 本机只有 3.16.3 的 asar，无 3.16.1 桌面包可 diff |

---

## 8. 一句话总结

Round 1 的**事实层**（门控顺序、参数集、state 结构、6 键、版本自愈）经独立复算基本可靠，坐标也可复现；问题集中在**四类**：①把桌面端当 3.16.1（实为 3.16.3）；②把 web 从不消费的 `remoteUrl/v3-v4` 写成入口行为；③`meta.name` 误判为与 Rust 一致；④几处生命周期细节反了（pending 清理时机、last-device 是否删除、设备分组键、`app_version` 是否透传、Kotlin 是否只有 ws2）。
