# 02 · Z.ai / BigModel OAuth 登录（ZCode Web 3.16.1）

**范围与方法**

- 事实来源仅限 `Reference_web/3.16.1/`（4 个指定 bundle）+ 项目内 Kotlin 源码；未执行快照 JS、未联网、未用真机。
- 坐标约定：`index@N` / `src@N` / `val@N` / `intl@N` 中的 N 均为**该文件内 UTF-8 字节偏移**，由 `Buffer.indexOf(Buffer.from(anchor,'utf8'))` 取得（不是 UTF-16 下标）。
- 四个文件的大小与 SHA-256 已独立复算，与 `docs/auth-3.16.1/source-index.md` 完全一致：

| 文件 | 字节数 | SHA-256（前 16 位） |
|---|---:|---|
| `assets/index-DbEHT6Gg.js` | 6,836,503 | `dc0d8128b394d624…` |
| `assets/src-uVqBd_Eq.js` | 70,714 | `93813e7e2b9ff436…` |
| `assets/validation-BBf1FA1a.js` | 331,153 | `7686853747e58b52…` |
| `assets/IntlProvider-Be9akOJ8.js` | 932,634 | `3cc6ba5331dc6025…` |

- 标记：`CONFIRMED`＝有原文坐标直接支持；`UNVERIFIED`＝代码里看不到；`SPECULATION`＝推断。
- 本报告**覆写**了同路径的上一版。上一版中 4 处偏移经复算为错（见附录 A），已更正；上一版引用 `app.asar` 的桌面端对照不在本轮允许的信源内，未复核，故不作为 3.16.1 网页事实继承（附录 B）。

---

## 1. 结论摘要

1. 网页版登录是**整页跳转**（`location.assign`），不是 WebView；回调也由整页加载 `/remote/v4?code=…&state=…` 触发，识别只看 **pathname + 参数存在性**（`index@6765087`），**不校验 host**。`CONFIRMED`
2. Z.ai 与 BigModel 是**两条不同的授权入口**：Z.ai 走标准 OAuth（`redirect_uri/response_type/client_id/state`），BigModel 走 `bigmodel.cn/login` 的私有参数（`redirect/appId/state`）。`CONFIRMED`
3. `state` = **无签名** `base64url(JSON.stringify({nonce, app_return_to[, return_to]}))`；`nonce = crypto.randomUUID()`，只存 `sessionStorage`，回调做字符串全等比对；**无时间戳、无过期、无签名**。`CONFIRMED`
4. `app_return_to` 与 `redirect_uri` 是两个不同的值：前者是当前完整 href，后者是配置的 `https://zcode.z.ai/remote/v4` **并会把 href 里的 `sid/mid/name/app_version` 复制过去**（`index@6717288`）。`CONFIRMED`
5. 换 token 只有一次服务端交换：`POST /api/v1/oauth/token`（**相对路径**，同源），仅 `Content-Type: application/json`，无 `Authorization`；body `{provider, code, redirect_uri, state}`。`CONFIRMED`
6. 响应契约：`code===0` 且 `data.token`（zcode JWT）与 `data.<provider>.access_token` 必须存在；两平台唯一分支差异就是取 `data.zai` 还是 `data.bigmodel`。`CONFIRMED`
7. 凭据**明文**存 localStorage，共 6 个键；`data.expires_in` 只换算成内存 `expiresAt`，**从不落盘**；本地过期只看 JWT 的 `exp`（30 s 余量，不验签）。`CONFIRMED`
8. 无 refresh、无跨标签页同步、无 `storage` 事件；`nonce` 只在发起授权的那个标签页的 sessionStorage 里，**换标签页完成授权必然 CSRF 失败**。`CONFIRMED`
9. 登录成功后不立即换 terminal token：先 `GET /api/v1/remote/device`（Bearer JWT），真正建 relay 连接时才 `POST /api/v1/remote/token {"role":"terminal"}` 换 22 字符短 token。`CONFIRMED`
10. 与当前 Android 实现有 **3 处真实风险差异**：`data.user` 只认 snake_case（网页端两种都认）、本地过期门用 `expires_in` 而非 JWT `exp`、回调 host 硬编码 `zcode.z.ai`。其余差异为文案/存储介质/加固策略。

---

## 2. 入口与状态机

### 2.1 启动分发（`ntr`，`index@6830751`）

```
async function ntr(){
  let e = new URLSearchParams(window.location.search);
  if (zer(e)) { Ber(); return }                       // 回调页
  if (g9n(window.location.pathname)) { await Ver(); return }  // 会话分享页
  if (Jer()) { await Y9.start(); return }              // pathname == /remote/v4
  ...                                                  // 其余（桌面/内嵌）分支
}
```

- `zer(e)`（`index@6803673`）= `H9n(当前URL)` 或（分享回调路径 且 有 state 且 有 code|authCode|error）。
- `Jer()`（`index@6811483`）= `cr(pathname,'/remote/v4')`，即 pathname 归一化后等于 `/remote/v4`（容忍尾斜杠）。
- `Y9 = new K9n({auth:q9, getHref, replaceHref: history.replaceState, storage: localStorage 包装, devices: E9n(origin), token: N9n(origin), connect: ttr, onView})`（`index@6805309`）；`q9 = P7n()` = `new N7n()`（`index@6803204`）。

### 2.2 控制器状态机（`K9n.start()`，`index@6765795`）

```
start():
  reset()
  if H9n(url)                          → view = callback
  else if !auth.getZCodeJwtToken()     → view = login
  else if mr(searchParams) == null     → failure invalid-mobile-connection
  else if !params.deviceSid            → openDevices(autoSelect=true)
  else                                 → connect(params)
```

- `mr` 是 `src` 的 `Ja`（`src@51830`）：`hash` 或 `t` 存在 → `null`；否则取 `sid→deviceSid / mid→deviceMid / name→deviceName / app_version→appVersion`，每个值 `trim()` 后空串丢弃（`Wa`，`src@51431`）。
- `openDevices()` 会读 `zcode-web-remote-last-device-sid/-mid` 做自动选中（`index@6765283`）。
- `connect()`（`index@6768630`）把 `getToken` 闭包交给传输层，闭包内每次调用都重新读 `getZCodeJwtToken()`；为空则 `Promise.reject(In('auth-required'))`。
- `startLogin(provider)`（`index@6769046`）= `auth.startLogin({...loginOptions, provider, appReturnTo: getHref()})`。

### 2.3 Z.ai 完整时序

```
[1] https://zcode.z.ai/remote/v4 无有效 JWT
    → K9n.start() → onView({kind:'login'}) → Her() @6805086 → 渲染 q5n @6708215

[2] 点「连接 Z.ai 继续使用」（q5n 遍历 [`zai`, Lr]，Lr='bigmodel'，故顺序 zai → bigmodel）
    → Y9.startLogin('zai') @6769046
    → auth.startLogin({provider:'zai', appReturnTo: location.href}) @6719738

[3] startLogin
    nonce        = j7n() = crypto.randomUUID()                    @6719045
    appReturnTo  = location.href
    redirectUri  = w7n(config.redirectUri, appReturnTo)           @6717288
    sessionStorage['oauth_pending_nonce']            = nonce      @6712266
    sessionStorage['oauth_pending_provider']         = 'zai'      @6712292
    sessionStorage['oauth_pending_redirect_uri']     = {"nonce":…,"redirectUri":…} @6712321
    state        = v7n({nonce, app_return_to: appReturnTo})       @6716473
    location.assign(buildAuthorizeUrl({state, provider:'zai', redirectUri})) @6710973

[4] 授权页（整页离开本站）
    https://chat.z.ai/api/oauth/authorize
      ?redirect_uri=<redirectUri>&response_type=code&client_id=client_P8X5CMWmlaRO9gyO-KSqtg&state=<state>
    → 302 回 redirect_uri，带 code 或 error，附原样 state

[5] 整页加载 https://zcode.z.ai/remote/v4?code=…&state=…
    ntr() → zer() 真 → Ber() @6803876（title='ZCode - Web Remote Control Sign In'）
    Ber 预解 state.app_return_to → C7n 净化，作为 onSuccess/onRetry 的落点
    G5n @6706530 useEffect → auth.handleCallback(location.href) @6720237

[6] handleCallback
    parseCallbackParams(url)          @6711375  ← 无 state 即抛
    y7n(state)                        @6716519  ← 解不出/无 nonce → 'OAuth state is invalid'
    [dev] return_to 跨源分支 → T7n + location.replace，返回 null（prod 不可达）
    if (error) throw 'OAuth login failed: <error>'
    if (!code) throw 'OAuth callback missing code'
    nonce !== sessionStorage.nonce → throw 'OAuth CSRF 检测失败'
    provider = pending ?? 'zai'
    redirectUri = pendingRedirectUri(state.nonce) ?? (分享回调 ? shareRedirectUri : config.redirectUri)
    清空 3 个 pending 键
    → exchangeToken({code, state, provider, redirectUri})

[7] POST /api/v1/oauth/token → Z5n 校验 @6710263
    saveTokenSet(tokenSet, provider)  @6712822
    saveUserInfo(rawUser, provider)   @6713030
    setActiveProvider(provider)       @6713116
    return {userInfo, appReturnTo: C7n(state.app_return_to)}

[8] Ber.onSuccess → window.location.replace(appReturnTo ?? '/remote/v4')
    → 重新 ntr() → K9n.start() → JWT 有效 → 设备列表/直连
```

### 2.4 BigModel 完整时序

与 Z.ai 同构，**只有 4 个点不同**：

| 步骤 | BigModel 取值 | 坐标 |
|---|---|---|
| 按钮 | `loginAction.bigmodel` = 「连接 BigModel 继续使用」+ region「中国」 | `index@6705353` 区块 |
| 授权 URL | `https://bigmodel.cn/login?redirect=<redirectUri>&appId=zcode&state=<state>` | `index@6710973` |
| 回调码字段 | 兼容 `authCode`（`authCode` 若存在会**覆盖** `code`，因对象展开顺序） | `index@6711375` |
| token 响应 | 读 `data.bigmodel.access_token` | `index@6710487` |

其余（state 结构、nonce 校验、存储键、回跳、错误分支）与 Z.ai 完全共用同一段代码。`CONFIRMED`

### 2.5 页面级状态机（登录页组件）

`q5n`（`index@6708215`）内部只有一个 `useState(false)`：点击任一按钮后置 `true`，**两个按钮同时 disabled 且永不恢复**（组件随即被整页跳转卸载）。`CONFIRMED`

`G5n`（`index@6706530`）有两个互斥视图：
- 等待：`callbackTitle`「正在完成登录」+ `callbackDescription`「请稍候，正在校验账号身份。」
- 失败：`callbackErrorTitle`「登录失败」+ `callbackErrorDescription`「授权流程未完成，请重新登录。」+ 灰框内**原样显示异常 message** + `retryAction`「重新登录」。

语言选择 `U5n`（`index@6706340`）：`document.documentElement.lang || navigator.language || navigator.languages[0]`，`toLowerCase().startsWith('zh')` → `zh-CN`，否则 `en-US`。**不读 URL 里的 `/cn/`**，与站内其他页面不同。

---

## 3. 逐字段表

### 3.1 运行时配置（`k7n` `index@6717957`，实例 `R9` `index@6718562`）

| 字段 | 生产值 | 来源 |
|---|---|---|
| `authorizeUrl` | `https://chat.z.ai/api/oauth/authorize` | `D7n` @6717798：`${new URL(origin).origin}/api/oauth/authorize`，缺省 origin `https://chat.z.ai` |
| `clientId` | `client_P8X5CMWmlaRO9gyO-KSqtg` | `VITE_ZAI_OAUTH_CLIENT_ID`，缺省同值 |
| `bigmodelAuthorizeUrl` | `https://bigmodel.cn/login` | `O7n` @6717881：`${origin \|\| At({})}/login` |
| `bigmodelAppId` | `zcode` | `VITE_BIGMODEL_OAUTH_APP_ID`，缺省 `zcode` |
| `tokenUrl` | `/api/v1/oauth/token`（**相对**） | 字面量 |
| `redirectUri` | `https://zcode.z.ai/remote/v4` | `validation` 的 `webRemoteCallbackUrl`（`${base}/remote/v4`） |
| `shareRedirectUri` | `https://zcode.z.ai/cn/share/callback` | `webShareCallbackUrl` |
| `allowDevReturnToRedirect` | `false` | `VITE_WEB_REMOTE_ALLOW_DEV_RETURN_TO === 'true'`，生产构建未注入 |

> `At({})` 只在上面的 env 为空时才会被求值；生产已注入 `VITE_BIGMODEL_OAUTH_ORIGIN=https://bigmodel.cn`，该分支不可达（`At` 的具体实现未定位，`UNVERIFIED`，不影响结论）。

### 3.2 Z.ai 授权 URL（`buildAuthorizeUrl` `index@6710973`）

```
https://chat.z.ai/api/oauth/authorize?redirect_uri=…&response_type=code&client_id=…&state=…
```

| 参数 | 值 | 说明 |
|---|---|---|
| `redirect_uri` | `w7n(config.redirectUri, appReturnTo)` | 只有 `appReturnTo` 的 pathname ∈ `/remote/v4(/)`、`/remote/__test__(/)` 时，才把 `app_version/sid/mid/name` 白名单参数（`p7n` @6715765）复制到 redirect_uri，并清空 hash；否则原样返回 `config.redirectUri` |
| `response_type` | `code` | 固定 |
| `client_id` | `client_P8X5CMWmlaRO9gyO-KSqtg` | 固定 |
| `state` | `base64url(JSON)` | 见 §3.5 |

顺序 = `URLSearchParams` 插入顺序（redirect_uri → response_type → client_id → state），编码 = `application/x-www-form-urlencoded`。`CONFIRMED`

### 3.3 BigModel 授权 URL（同一函数另一分支）

```
https://bigmodel.cn/login?redirect=…&appId=zcode&state=…
```

| 参数 | 值 | 与 Z.ai 的差异 |
|---|---|---|
| `redirect` | 同 `redirect_uri` | **参数名不同** |
| `appId` | `zcode` | 取代 `client_id` |
| `state` | 同 | 同 |
| — | — | **无** `response_type`、**无** `client_id` |

### 3.4 token 交换请求（`exchangeToken` `index@6711731`）

| 项 | 值 |
|---|---|
| Method / URL | `POST` + `this.config.tokenUrl` = 相对 `/api/v1/oauth/token` → 解析到页面 origin `https://zcode.z.ai/api/v1/oauth/token` |
| Headers | 仅 `Content-Type: application/json`；**无 Authorization、无自定义头** |
| Credentials | 未设置 → 浏览器默认 `same-origin`，同源 cookie 会带上（但整包 0 次 `document.cookie`，无自设 cookie） |
| Body | `{"provider":"zai"\|"bigmodel","code":"…","redirect_uri":"…","state":"<base64url>"}` |
| `provider` 缺省 | `exchangeToken` 内 `e.provider ?? 'zai'`，但 `handleCallback` 总会传 |
| HTTP 非 2xx | `throw Error('OAuth token exchange failed with HTTP <status>')`，401/5xx 同一句 |
| 响应非 JSON | `await n.json()` 抛原生 SyntaxError，未包装 |

### 3.5 token 交换响应（`Z5n` `index@6710263`）

| 字段 | 类型 / 必填 | 消费方式 | 缺失/非法时 |
|---|---|---|---|
| `code` | int，必须 `=== 0` | 严格全等判断 | `code!==0`（**含字段缺失**，`undefined!==0`）→ `throw Error(msg?.trim() \|\| 'OAuth token exchange failed')` |
| `msg` | string | 仅用于错误文案 | 空则用默认文案 |
| `data.token` | string | `trim()` 后即 **zcode JWT** | `'Token exchange response missing data.token'` |
| `data.zai.access_token` | string | Z.ai 分支：`trim()` | `'Token exchange response missing data.zai.access_token'` |
| `data.bigmodel.access_token` | string | BigModel 分支：`trim()`（`index@6710469`） | `'Token exchange response missing data.bigmodel.access_token'` |
| `data.user` | object，可选 | 经 `A9` 归一化；失败则回落 `{id:'unknown',username:'user',displayName:'User'}` | **不报错**（见下方死分支说明） |
| `data.expires_in` | number（秒），可选 | `expiresAt = now() + expires_in*1000`，**只进内存** | 无过期时间 |

**两平台响应差异**：仅 `data.<provider>.access_token` 这一条分支（`index@6710469`）。`data.token` / `data.user` / `data.expires_in` 走同一条路径，因此结构相同。`CONFIRMED`

`A9`（`index@6709691`）的用户归一化规则：
1. 先认驼峰：`{id:string, username:string, displayName:string, avatarUrl?:string}` → 直接采用；
2. 否则按蛇形：`id = user_id ?? 'unknown'`，`username = displayName = name || email || user_id`；三者皆空且 id 为 `unknown` → `null`；
3. `avatar` 字段经 `Y5n`（`index@6709477`）：`data:image/...` 或 `http(s)://` 原样；纯 base64（`^[A-Za-z0-9+/]+={0,2}$` 且长度 ≥16）补 `data:image/png;base64,` 前缀；其余原样。

**死分支说明**：`Z5n` 里 `let a = e.data?.user && A9(e.data.user) ? e.data.user : {id:'unknown',username:'user',displayName:'User'}; let o = A9(a); if(!o) throw Error('Token exchange response missing user info')`。回落对象必然被 `A9` 接受（三个字段都是 string），因此 `missing user info` 这个 throw **不可达**；缺 `data.user` 永远走回落。`CONFIRMED`

### 3.6 存储键表（网页端）

| 键 | 存储 | 值形态 | 写入 | 清除 |
|---|---|---|---|---|
| `oauth:active_provider` | localStorage | `"zai"` \| `"bigmodel"` | `setActiveProvider` @6713116 | `clearAll` |
| `zcodejwttoken` | localStorage | JWT 原文（= `data.token`） | `saveTokenSet` @6712822（空则 `removeItem`） | `clearAll` |
| `oauth:zai:access_token` | localStorage | 平台 access_token 原文 | `saveTokenSet` | `clearAll` |
| `oauth:zai:user_info` | localStorage | `JSON.stringify(服务端原始 user)`（**不是归一化后的对象**） | `saveUserInfo` @6713030 | `clearAll` |
| `oauth:bigmodel:access_token` | localStorage | 同上 | `saveTokenSet` | `clearAll` |
| `oauth:bigmodel:user_info` | localStorage | 同上 | `saveUserInfo` | `clearAll` |
| `oauth_pending_nonce` | sessionStorage | UUID 字符串 | `savePendingNonce` | 回调校验通过后立即清 |
| `oauth_pending_provider` | sessionStorage | `zai`/`bigmodel` | `savePendingProvider` | 同上 |
| `oauth_pending_redirect_uri` | sessionStorage | `{"nonce":"…","redirectUri":"…"}` | `savePendingRedirectUri` @6715107 | 同上 |
| `zcode-web-remote-last-device-sid` | localStorage | 字符串 | `rememberDevice` | 不清理 |
| `zcode-web-remote-last-device-mid` | localStorage | 字符串 | `rememberDevice` | 不清理 |
| `zcode-web-remote-device-names` | localStorage | `[[mid,name,platform?],…]`，≤50 条 | `V9n`（写入调用点 `index@6764722`） | 不清理 |

- **明文、无加密**；无 IndexedDB（整包 0 次）、无 BroadcastChannel、无 `document.cookie`；整包仅 2 处 `storage` 事件监听，均与 auth 键无关（标签页排序 / `zcode:developer-tools:enabled`、`zcode:token-debug:enabled`）。`CONFIRMED`
- 键名常量：`j9` @6712100 / `M9` @6712127 / `$5n` @6712146 / `e7n` @6712175 / `t7n` @6712201 / `n7n` @6712235 / `r7n` @6712266 / `i7n` @6712292 / `a7n` @6712321。
- **过期策略**：只有 `zcodejwttoken` 的 JWT `exp` 参与判断（`xr`，`src@31023`；`now+30s >= exp*1000` 即 expired，缺 `exp` 返回 `unknown` 且**不拦截**）。`data.expires_in` 换算出的 `expiresAt` 只存在于 `tokenSet` 内存对象，`saveTokenSet` **只写 accessToken 与 jwt**，刷新页面即丢。`CONFIRMED`
- **刷新/续期**：**无 refresh_token**、无续期请求、无静默续期；JWT 过期只能重新登录（`loadCachedSessionState` → `clearAll` + `reauthentication-required`）。`CONFIRMED`
- **登出**：`logout()` @6721856 → `clearAll()` @6714525，删上表 6 个 localStorage 键；**不动** `oauth_pending_*`（正常流程在回调时已清）与 `zcode-web-remote-*` 设备缓存键。`CONFIRMED`
- **刷新后恢复**：整页刷新 → `ntr()` → `Y9.start()` → `getZCodeJwtToken()` → `loadCachedSessionState()` 从 localStorage 重建（校验 active_provider 合法、jwt/access_token/user_info 非空、JWT 未过期）→ 通过则进设备列表，否则登录页。`CONFIRMED`
- **跨标签页**：**无 `storage` 事件同步**（A 标签登出后，B 标签要等下一次 `getZCodeJwtToken()` 或刷新才发现）；且 `nonce` 在 sessionStorage，**换标签页完成授权必然 `OAuth CSRF 检测失败`**（E5）。`CONFIRMED`
- `oauth_pending_*` 在 `handleCallback` 中**先清后用**（清完才 `exchangeToken`）：交换失败后无法重试，必须重新发起授权。`CONFIRMED`

---

## 4. state 的生成与校验

| 环节 | 实现 | 坐标 |
|---|---|---|
| nonce | `crypto.randomUUID()`；无则 `getRandomValues(16B)` 转 hex；都不可用抛 `Secure random generator is unavailable` | `j7n` @6719045 |
| 编码 | `base64url(utf8(JSON.stringify(obj)))`：`btoa` → `+`→`-`、`/`→`_`、去 `=` | `g7n` @6716085 / `v7n` @6716473 |
| 载荷 | `{nonce, app_return_to, [return_to]}`；`return_to` 仅 dev 且先经 `w7n` 净化 | `startLogin` @6719738 |
| 解码 | 反向 `-`→`+`、`_`→`/`、补 `=`、`atob`、`JSON.parse`；`nonce` 必须为非空字符串 | `y7n` @6716519 |
| 落地 | `sessionStorage['oauth_pending_nonce']`（**仅此一处**，非 cookie、非 URL） | `r7n` @6712266 |
| 校验 | 解码 `nonce` 与 sessionStorage **字符串全等**；不等 → `Error('OAuth CSRF 检测失败')` | `handleCallback` @6720237 |
| 签名 | **无** | — |
| 过期 | **无**（无 `iat/exp/ts`） | — |
| 一次性 | 校验通过即删 3 个 pending 键 | `handleCallback` |

**安全性结论**：伪造 `state` 可以控制 `app_return_to`，但 `C7n`（`index@6717014`）把回跳限制在**当前 origin** + pathname ∈ `/remote/v4(/)`、`/remote/__test__(/)`、`/^(?:cn\/share|share)\/[A-Za-z0-9._~-]{1,512}$/`，query 只保留 `app_version/sid/mid/name`，并丢弃 hash → 不构成开放重定向。伪造 `state` 无法通过 nonce 全等校验。`CONFIRMED`

**`app_return_to` vs `redirect_uri`**：前者是用户浏览器当前 href（原样进 state），后者是配置值 + 参数复制（进授权请求与 token 请求）。`handleCallback` 返回的 `appReturnTo` 是 `C7n(state.app_return_to)` 的**相对路径**（如 `/remote/v4?sid=…`），不是绝对 URL。`CONFIRMED`

---

## 5. 回调识别、历史清理与全部错误路径

### 5.1 回调识别

| 判定 | 规则 | 坐标 |
|---|---|---|
| 启动期 | `zer(searchParams)`：`H9n(url)` 或（分享回调路径 + state + code\|authCode\|error） | `index@6803673` |
| 控制器 | `H9n(url)`：pathname ∈ `{/remote/v4, /remote/v4/}` **且** 有 `state` **且** 有 `code`/`authCode`/`error` 之一 | `index@6765087` |
| host | **不校验**（依赖页面本身就在同源） | — |
| provider | **不在回调 URL 里**，只从 `sessionStorage['oauth_pending_provider']` 读，缺省 `zai` | `handleCallback` |
| 参数解析 | `state` 必填（缺失即抛）；`code` 与 `authCode` 都收，后者覆盖前者；`error` 单独收 | `index@6711375` |

### 5.2 浏览器历史清理

- 回调页成功后：`window.location.replace(appReturnTo ?? '/remote/v4')`（`Ber` @6803876），**替换**当前历史项，callback 的 `?code&state` 不留在历史里；失败点「重新登录」同样 `replace`。
- 控制器选设备时：`replaceHref` = `history.replaceState(history.state,'',url)`（`index@6805380` 区块），把 `sid/mid/name` 写进 URL 但不新增历史项；`chooseOtherDevice` 删除 `sid/mid/name/hash/t` 后同样 `replaceState`。
- **泄漏窗口**：`/remote/v4?code=…&state=…` 这一整页加载期间，code 在地址栏、`document.referrer`、以及浏览器历史里存在，直到 SPA `replace`。服务端是否 302 到无 query 的地址未知（`UNVERIFIED`）。

### 5.3 错误分支全表（网页端）

| # | 场景 | 触发点 | 结果 / 用户可见 |
|---|---|---|---|
| E1 | 回调缺 `state` | `parseCallbackParams` | throw `OAuth callback missing state` |
| E2 | `state` 非 base64url JSON / 缺 `nonce` | `y7n` → null | throw `OAuth state is invalid` |
| E3 | 授权页返回 `error=…`（如 `access_denied`、`invalid_state`） | `handleCallback` | throw `OAuth login failed: <error>`（**原始字符串直出**，客户端不枚举错误码） |
| E4 | 有 state 无 code/authCode | `handleCallback` | throw `OAuth callback missing code` |
| E5 | `state.nonce` ≠ sessionStorage（含新标签页、sessionStorage 被清） | `handleCallback` | throw `OAuth CSRF 检测失败` |
| E6 | token 接口非 2xx（401/403/5xx 同句） | `exchangeToken` | throw `OAuth token exchange failed with HTTP <status>` |
| E7 | token 响应非 JSON | `await n.json()` | 原生 `SyntaxError`（未包装，文案为浏览器原文） |
| E8 | 业务 `code!==0` 或 `code` 缺失 | `Z5n` | throw `msg`（服务端文案）或 `OAuth token exchange failed` |
| E9 | 缺 `data.token` | `Z5n` | throw `Token exchange response missing data.token` |
| E10 | 缺 `data.<provider>.access_token`（**平台选错的实际拦截点**） | `Z5n` | throw `Token exchange response missing data.<p>.access_token` |
| E11 | 缺 `data.user` 或 user 非法 | `Z5n` | **不报错**，回落 `{id:'unknown',username:'user',displayName:'User'}` |
| E12 | 网络不可达 | `fetch` | 原生 `TypeError: Failed to fetch` 直出 |
| E13 | 随机数不可用 | `j7n` | throw `Secure random generator is unavailable`（登录按钮点击即失败） |
| E14 | 凭据不全（缺 active_provider / jwt / access_token / user_info 任一） | `loadCachedSessionState` @6713613 | `hasAnyStoredCredential()` 为真则 `clearAll()`，返回 `signed-out` → 登录页 |
| E15 | JWT `exp` 已过（30 s 余量） | `xr`（`src@31023`，主包以 `Zi` 引用） | `clearAll()`，返回 `{status:'reauthentication-required', reason:'jwt-expired'}` |
| E16 | `user_info` JSON 解析失败 | `loadCachedSessionState` | `clearAll()` + `signed-out` |
| E17 | 设备列表 401 | `E9n` @6747754 | `In('auth-required')` → `switchAccount()`（logout + 登录页） |
| E18 | terminal token 401 | `N9n` @6762730 | `In('auth-required')` → `switchAccount()` |
| E19 | 桌面端会话恢复遇到 E15 | `t8n`/`e8n` @6587445 | 弹「登录已过期 / 为了保障账号安全，请重新登录。 / 重新登录」并触发 `onReauthenticationRequired`（**桌面端 Root 路径，不是 `/remote/v4` 页**） |

**用户可见文案（`/remote/v4` 页内联 `H5n`，不走 i18n）** `index@6705325`：

| key | zh-CN | en-US |
|---|---|---|
| `brand` | ZCode | ZCode |
| `loginTitle` | 登录后继续使用 Web 远程控制 | Sign In To Continue |
| `loginDescription` | 请选择与电脑端相同的登录平台，并使用同一账号。 | Choose the same sign-in provider and account as on your computer. |
| `loginAction.zai` | 连接 Z.ai 继续使用 | Connect to Z.ai |
| `loginAction.bigmodel` | 连接 BigModel 继续使用 | Connect to BigModel |
| `loginRegion.zai` | 全球 | Global |
| `loginRegion.bigmodel` | 中国 | CN |
| `callbackTitle` | 正在完成登录 | Finishing Sign-In |
| `callbackDescription` | 请稍候，正在校验账号身份。 | Verifying your account identity. |
| `callbackErrorTitle` | 登录失败 | Sign-In Failed |
| `callbackErrorDescription` | 授权流程未完成，请重新登录。 | The authorization flow did not complete. Sign in again. |
| `retryAction` | 重新登录 | Sign In Again |

> `IntlProvider`（`intl@76200` 区块）里另有一套 `login.oauth.button.zai` / `login.expired.title` / `login.apiKey.*` 文案，属于**桌面端登录界面**的 i18n，`/remote/v4` 不使用。不要混淆。

---

## 6. 登录后如何把凭据交给远控（只到接口边界）

```
登录成功
  → localStorage['zcodejwttoken'] = data.token（zcode JWT）
  → K9n.connect() @6768630 提供 getToken 闭包：
        getToken(signal) = auth.getZCodeJwtToken() 为空 → reject In('auth-required')
                         否则 options.token(jwt, signal)
  → N9n @6762730：
        POST https://<pageOrigin>/api/v1/remote/token
        Authorization: Bearer <zcode JWT>
        Content-Type: application/json
        body: {"role":"terminal"}
        timeout 15s, redirect:'error'
        401 → In('auth-required')；非 2xx → In('relay-unavailable')
        响应须 code===0 且 data.token 匹配 /^[A-Za-z0-9_-]{21}[AQgw]$/（src@53993）
  → 得到 22 字符 terminal token + data.expires_in
  → 交给传输层拼 wss://<host>/api/v1/remote/ws2?token=<短token>&mid=<deviceMid>
     （ws2 URL 构造在 validation @125393：`${wssOrigin}/api/v1/remote/ws2`）
```

- 设备列表：`GET /api/v1/remote/device`，`Authorization: Bearer <zcode JWT>`，`cache:'no-store'`（`E9n` @6747754）。API 路径表在 `src@53637`（`io.token.path='/api/v1/remote/token'` 等）。
- **平台 access_token 不参与远控**：`oauth:<provider>:access_token` 只被 coding-plan 内嵌页/奖励页注入使用（`index@2549759` 构造 `localStorage.setItem` 脚本；`src@63079` 同类脚本）。
- `getToken` 闭包内**实时**读 `getZCodeJwtToken()`，terminal token 不做本地缓存；传输层在首连/重连时何时再次调用该闭包属于 03 路范围，本报告不下结论。

---

## 7. 与当前 Android 实现的差异清单

对照文件：
`android/app/src/main/kotlin/app/zemote/auth/OAuth.kt`、
`ui/auth/LoginViewModel.kt`、`ui/auth/LoginScreen.kt`、`ui/auth/LoginOverlay.kt`、
`ui/AppContainer.kt`、`ui/UiFailure.kt`、`data/AuthStore.kt`。

### 7.1 真实风险（建议修）

| # | 项 | 网页端 | Android | 影响 |
|---|---|---|---|---|
| **R1** | `data.user` 字段形态 | `A9` 同时支持驼峰 `{id,username,displayName,avatarUrl}` 与蛇形 `{user_id,name,email,avatar}`；裸 base64 头像补 `data:` 前缀 | `OAuth.kt` L325-335 只读 `name/email/user_id`、`avatar/avatar_url`，**不认** `displayName/username/avatarUrl`，也不补 data URI | 若服务端返回驼峰（网页端 `A9` 的第一优先级），Android 显示名退化为「已登录」、头像丢失 |
| **R2** | 本地过期判据 | 只认 JWT `exp`（`xr`：`now+30s >= exp*1000`），`expires_in` 不落盘 | `AuthStore.kt` L94 用 `expiresAt`（来自 `expires_in`）硬门；无 `expires_in` 时永不过期 | 若 `expires_in` 是 access_token TTL 而 JWT 更长 → 提前强制重登；反之则只能等 401 |
| **R3** | 回调 host 校验 | `H9n` 只看 pathname，不校验 host（天然同源） | `OAuth.kt` L168-177 要求 `https` 且 host == `zcode.z.ai` 且 path == `/remote/v4` | 若部署 origin 变化（staging/自建域名），Android 收不到回调 |

### 7.2 行为差异（无故障风险）

| # | 项 | 网页端 | Android |
|---|---|---|---|
| D1 | `state.app_return_to` | 当前 `location.href`（含 `sid/mid/name/app_version`） | `OAuth.kt` L143 填的是 `redirectUri` 常量 |
| D2 | `redirect_uri` 参数复制 | 把 href 的 `app_version/sid/mid/name` 复制进 redirect_uri，清 hash | 恒为 `https://zcode.z.ai/remote/v4`，不带 query |
| D3 | `return_to`（dev 转发） | 支持，但生产 `allowDevReturnToRedirect=false` | 未实现（等价于生产行为） |
| D4 | nonce 存放 | sessionStorage（整页跳转后仍在） | ViewModel 内存（旋转存活、进程被杀丢失） |
| D5 | 回调 provider 来源 | sessionStorage，缺省 `zai` | 内存中的 `request.provider`（更准确） |
| D6 | 校验顺序 | `error` → `code` → `nonce` | `nonce` → `error` → `code`（L184-198）→ 两者同时异常时文案不同 |
| D7 | `code` vs `authCode` | `authCode` 覆盖 `code` | `code ?: authCode`（`code` 优先） |
| D8 | token 响应 `code` 缺失 | 视为失败（`undefined!==0`） | 视为成功（`code!=null && code!=0` 才报错） |
| D9 | 请求头 | 仅 `Content-Type` | 多 `Accept: application/json`（L286） |
| D10 | token URL | 相对路径（同源） | 绝对 `https://zcode.z.ai/api/v1/oauth/token` |
| D11 | 存储 | localStorage 明文 6 键 + 原始 user JSON | DataStore + Keystore AES-GCM 密文（`enc:v1:`），只存 provider/displayName/avatarUrl/expiresAt/JWT |
| D12 | 平台 access_token / active_provider | 存 | 不存（不消费，无需对齐） |
| D13 | 登出 | `clearAll()` 6 键；不动设备缓存键 | `clear()` 只删 `zemote_auth_v1` |
| D14 | 凭据损坏 | `clearAll()` 真删 | 解密失败返回 null，**不删密文**（更保守） |
| D15 | 失败展示 | 独立失败页（标题+说明+原始 message+重新登录） | 回平台选择页，红字一行 |
| D16 | 文案 | `OAuth login failed: <e>` / `OAuth CSRF 检测失败` / `OAuth state is invalid` / `OAuth callback missing code` | `授权失败：<e>` / `OAuth CSRF 校验失败` / `state 不是合法格式` / `回调缺少授权码` / `回调 URL 无法解析` |
| D17 | 回调后 URL 清理 | `location.replace` 抹 query | WebView 拦截，不导航（无需清理） |
| D18 | terminal token | 每次连接都换，校验 22 字符正则 | `RemoteTokenClient` 每次 `exchange`，不校验正则；body code 401 也映射 `auth-required`（网页端只认 HTTP 401） |
| D19 | 登录页文案 | 标题+说明+两按钮+region chip | 一致，另多一行「Z.ai 与 BigModel 的账号数据不互通…」 |
| D20 | 分享回调 `/cn/share/callback` | 支持 | 未实现（Android 无分享页） |

### 7.3 Android 侧实现位置

| 关注点 | 位置 |
|---|---|
| 授权 URL 拼装 | `OAuth.kt` L138-160（`OAuthFlow.create`） |
| state 编码 | `OAuth.kt` L201-211（键顺序与网页一致：`nonce` → `app_return_to`，base64url 无 padding） |
| 回调判定 / 解析 | `OAuth.kt` L168-177 / L184-198 |
| token 交换 | `OAuth.kt` L267-343 |
| terminal token | `OAuth.kt` L375-424；接线 `AppContainer.kt` L150 `terminalTokenProvider` |
| 登录 UI | `LoginScreen.kt` L145-176（文案）/ L209-261（WebView） |
| 状态机 | `LoginViewModel.kt` L55-131 |
| 凭据 | `AuthStore.kt` L92-129 |
| 失败文案 | `UiFailure.kt` L28（`RemoteTokenException → message`） |

---

## 8. 证据索引

> 每条格式：`@字节偏移` + 原文片段（截断处用 `…`，均 ≤500 字符）。偏移与 SHA-256 绑定，文件变化即失效。

### 8.1 `Reference_web/3.16.1/assets/index-DbEHT6Gg.js`

```text
@6705325  H5n={"zh-CN":{brand:`ZCode`,loginTitle:`登录后继续使用 Web 远程控制`,loginDescription:`请选择与电脑端相同的登录平台，并使用同一账号。`,loginAction:{zai:`连接 Z.ai 继续使用`,bigmodel:`连接 BigModel 继续使用`},loginRegion:{zai:`全球`,bigmodel:`中国`},callbackTitle:`正在完成登录`,callbackDescription:`请稍候，正在校验账号身份。`,callbackErrorTitle:`登录失败`,callbackErrorDescription:`授权流程未完成，请重新登录。`,retryAction:`重新登录`}
@6706340  function U5n(e){return(e??document.documentElement.lang??navigator.language??navigator.languages?.[0]??``).toLowerCase().startsWith(`zh`)?`zh-CN`:`en-US`}
@6706530  function G5n({authService:e,onRetry:t,onSuccess:n}){let r=W5n(),[i,a]=(0,Q.useState)(null);return(0,Q.useEffect)(()=>{let t=!0;return e.handleCallback(window.location.href).then(e=>{!t||!e||n(e)}).catch(e=>{t&&a(e instanceof Error?e.message:String(e))}),()=>{t=!1}},[e,n]),…}
@6708215  function q5n({onLogin:e}){…(0,$.jsx)(`div`,{className:`space-y-2`,children:[`zai`,Lr].map(i=>(0,$.jsxs)(X,{…onClick:()=>{r(!0),e(i)},children:[IL(i,`size-4`),…t.loginAction[i],…t.loginRegion[i]]},i))})}
@6709477  function Y5n(e){if(typeof e!=`string`)return;let t=e.trim();if(t)return/^data:image\/[^;]+;base64,/i.test(t)||/^https?:\/\//i.test(t)?t:/^[A-Za-z0-9+/]+={0,2}$/.test(t)&&t.length>=16?`data:image/png;base64,${t}`:t}
@6709691  function A9(e){if(!J5n(e))return null;if(typeof e.id==`string`&&typeof e.username==`string`&&typeof e.displayName==`string`)return{id:e.id,username:e.username,displayName:e.displayName,…};let t=typeof e.user_id==`string`?e.user_id:`unknown`,n=typeof e.name==`string`?e.name.trim():``,r=typeof e.email==`string`?e.email.trim():``,i=n||r||t;…}
@6710198  function X5n(e,t){if(!(!e||!Number.isFinite(e)))return t()+e*1e3}
@6710263  function Z5n(e,t,n){if(e.code!==0)throw Error(e.msg?.trim()||`OAuth token exchange failed`);let r=e.data?.token?.trim();if(!r)throw Error(`Token exchange response missing data.token`);let i=(n===`bigmodel`?e.data?.bigmodel?.access_token:e.data?.zai?.access_token)?.trim();if(!i)throw Error(`Token exchange response missing data.${n}.access_token`);…}
@6710908  var Q5n=class{constructor(e,t=Date.now){this.config=e,this.now=t}buildAuthorizeUrl(e){…}
@6710973  buildAuthorizeUrl(e){let t=e.redirectUri??this.config.redirectUri;if(e.provider===`bigmodel`){let n=new URLSearchParams({redirect:t,appId:this.config.bigmodelAppId,state:e.state});return`${this.config.bigmodelAuthorizeUrl}?${n.toString()}`}let n=new URLSearchParams({redirect_uri:t,response_type:`code`,client_id:this.config.clientId,state:e.state});return`${this.config.authorizeUrl}?${n.toString()}`}
@6711375  parseCallbackParams(e){let t=new URL(e),n=t.searchParams.get(`state`)?.trim();if(!n)throw Error(`OAuth callback missing state`);return{state:n,…t.searchParams.get(`code`)?{code:t.searchParams.get(`code`)}:{},…t.searchParams.get(`authCode`)?{code:t.searchParams.get(`authCode`)}:{},…t.searchParams.get(`error`)?{error:t.searchParams.get(`error`)}:{}}}
@6711731  async exchangeToken(e){let t=e.provider??`zai`,n=await fetch(this.config.tokenUrl,{method:`POST`,headers:{"Content-Type":`application/json`},body:JSON.stringify({provider:t,code:e.code,redirect_uri:e.redirectUri??this.config.redirectUri,state:e.state})});if(!n.ok)throw Error(`OAuth token exchange failed with HTTP ${n.status}`);return Z5n(await n.json(),this.now,t)}
@6712100  j9=`oauth:active_provider`,M9=`zcodejwttoken`,$5n=`oauth:zai:access_token`,e7n=`oauth:zai:user_info`,t7n=`oauth:bigmodel:access_token`,n7n=`oauth:bigmodel:user_info`,r7n=`oauth_pending_nonce`,i7n=`oauth_pending_provider`,a7n=`oauth_pending_redirect_uri`
@6712354  function o7n(e){return e===`zai`||e===`bigmodel`}function N9(e){return e===`bigmodel`?{accessToken:t7n,userInfo:n7n}:{accessToken:$5n,userInfo:e7n}}
@6712655  var c7n=class{localStorage;sessionStorage;now;constructor(e=s7n(),t={}){…}saveTokenSet(e,t){let n=`zaiAccessToken`in e?e.zaiAccessToken:e.accessToken,r=e.zcodeJwtToken;this.localStorage.setItem(N9(t).accessToken,n),r?this.localStorage.setItem(M9,r):this.localStorage.removeItem(M9)}…
@6713613  loadCachedSessionState(){let e=this.localStorage.getItem(j9),t=this.localStorage.getItem(M9),n=o7n(e)?N9(e):null,r=n?this.localStorage.getItem(n.accessToken):null,i=n?this.localStorage.getItem(n.userInfo):null;if(!n||!P9(t)||!P9(r)||!P9(i))return this.hasAnyStoredCredential()&&this.clearAll(),{status:`signed-out`};if(Zi(t,this.now()).kind===`expired`)return this.clearAll(),{status:`reauthentication-required`,reason:`jwt-expired`};…
@6714174  loadZCodeJwtToken(){return this.loadCachedSessionState().status===`authenticated`&&this.localStorage.getItem(M9)?.trim()||null}
@6714525  clearAll(){this.localStorage.removeItem(j9),this.localStorage.removeItem(M9),this.localStorage.removeItem($5n),this.localStorage.removeItem(e7n),this.localStorage.removeItem(t7n),this.localStorage.removeItem(n7n)}
@6715107  savePendingRedirectUri(e,t){this.sessionStorage.setItem(a7n,JSON.stringify({nonce:e,redirectUri:t}))}loadPendingRedirectUri(e){…if(n&&…n.nonce===e&&…n.redirectUri.trim())return n.redirectUri…}
@6715543  l7n=N,F9=new Set([`/remote/v4`,`/remote/v4/`]),u7n=new Set([...F9,`/remote/__test__`,`/remote/__test__/`]),d7n=new Set([...F9,`/cn/share/callback`,`/share/callback`])
@6715765  p7n=new Set([`app_version`,`sid`,`mid`,`name`])
@6716085  function g7n(e){let t=new TextEncoder().encode(e),n=``;for(let e of t)n+=String.fromCharCode(e);return btoa(n).replace(/\+/g,`-`).replace(/\//g,`_`).replace(/=+$/,``)}
@6716473  function v7n(e){return g7n(JSON.stringify(e))}function y7n(e){try{let t=JSON.parse(_7n(e));return I9(t.nonce)?{nonce:t.nonce,…I9(t.app_return_to)?{app_return_to:t.app_return_to}:{},…I9(t.return_to)?{return_to:t.return_to}:{}}:null}catch{return null}}
@6717014  function C7n(e,t={}){let n=L9(e);if(!n||!u7n.has(n.pathname)&&!f7n.test(n.pathname))return null;let r=x7n(t.currentOrigin??h7n());if(n.origin!==r)return null;if(f7n.test(n.pathname))return n.pathname;let i=S7n(n.searchParams).toString();return`${n.pathname}${i?`?${i}`:``}`}
@6717288  function w7n(e,t,n={}){let r=L9(e),i=L9(t);return!r||!F9.has(r.pathname)||!i||!u7n.has(i.pathname)||!C7n(t,n)?e:(r.search=S7n(i.searchParams).toString(),r.hash=``,r.toString())}
@6717465  function T7n(e,t){let n=L9(e);return!n||!b7n(n)||!I9(t.state)?null:(n.search=F9.has(n.pathname)?S7n(n.searchParams).toString():``,n.hash=``,…n.searchParams.set(`state`,t.state),n.toString())}
@6717798  function D7n(e){return`${E7n(e?.trim()||`https://chat.z.ai`)}/api/oauth/authorize`}
@6717881  function O7n(e){let t=e?.trim();return`${t?new URL(t).origin:At({})}/login`}
@6717957  function k7n(e={}){…return{authorizeUrl:D7n(e.VITE_ZAI_OAUTH_ORIGIN),tokenUrl:`/api/v1/oauth/token`,clientId:e.VITE_ZAI_OAUTH_CLIENT_ID?.trim()||`client_P8X5CMWmlaRO9gyO-KSqtg`,bigmodelAuthorizeUrl:O7n(e.VITE_BIGMODEL_OAUTH_ORIGIN),bigmodelAppId:e.VITE_BIGMODEL_OAUTH_APP_ID?.trim()||`zcode`,redirectUri:n.webRemoteCallbackUrl,shareRedirectUri:n.webShareCallbackUrl,…allowDevReturnToRedirect:e.VITE_WEB_REMOTE_ALLOW_DEV_RETURN_TO===`true`}}
@6718562  var R9=k7n({BASE_URL:`/remote/v4/3.16.1/`,DEV:!1,MODE:`production`,PROD:!0,SSR:!1,VITE_BIGMODEL_OAUTH_ORIGIN:`https://bigmodel.cn`,VITE_WEB_REMOTE_CONTROL_ROUTE_PATH:`/remote/v4`,VITE_ZAI_OAUTH_CLIENT_ID:`client_P8X5CMWmlaRO9gyO-KSqtg`,VITE_ZAI_OAUTH_ORIGIN:`https://chat.z.ai`,VITE_ZCODE_BASE_URL:`https://zcode.z.ai`,VITE_ZCODE_ENDPOINT_ORIGIN:`https://zcode.z.ai`})
@6719045  function j7n(){if(globalThis.crypto?.randomUUID)return globalThis.crypto.randomUUID();if(!globalThis.crypto?.getRandomValues)throw Error(`Secure random generator is unavailable`);…}
@6719738  startLogin(e={}){let t=this.runtime.createNonce(),n=e.provider??`zai`,r=e.appReturnTo??this.runtime.getCurrentHref(),i={currentOrigin:this.runtime.getCurrentOrigin()},a=w7n(e.redirectUri??this.config.redirectUri,r,i);this.repo.savePendingNonce(t),this.repo.savePendingProvider(n),this.repo.savePendingRedirectUri(t,a);let o=v7n({nonce:t,app_return_to:r,…e.devReturnTo?{return_to:w7n(e.devReturnTo,r,i)}:{}});this.runtime.assign(this.provider.buildAuthorizeUrl({state:o,provider:n,redirectUri:a}))}
@6720237  async handleCallback(e){let t=this.provider.parseCallbackParams(e),n=y7n(t.state);if(!n)throw Error(`OAuth state is invalid`);let r=L9(n.return_to);if(r&&r.origin!==this.runtime.getCurrentOrigin()&&this.config.allowDevReturnToRedirect&&b7n(r)){…}if(t.error)throw Error(`OAuth login failed: ${t.error}`);if(!t.code)throw Error(`OAuth callback missing code`);let i=this.repo.loadPendingNonce();if(n.nonce!==i)throw Error(`OAuth CSRF 检测失败`);let a=this.repo.loadPendingProvider()??`zai`,o=this.repo.loadPendingRedirectUri(n.nonce);this.repo.clearPendingNonce(),this.repo.clearPendingProvider(),this.repo.clearPendingRedirectUri();…
@6721586  getZCodeJwtToken(){return this.repo.loadZCodeJwtToken()}
@6747754  function E9n(e,t=fetch){return async(n,r)=>{…new URL(Er.list.path,e),{method:`GET`,headers:{Authorization:`Bearer ${n}`},cache:`no-store`,signal:AbortSignal.any([r,AbortSignal.timeout(15e3)]),redirect:`error`});if(i.status===401)throw new In(`auth-required`);…}}
@6762730  function N9n(e,t=fetch){return async(n,r)=>{…new URL(Er.token.path,e),{method:`POST`,headers:{Authorization:`Bearer ${n}`,"Content-Type":`application/json`},body:JSON.stringify({role:`terminal`}),…redirect:`error`});if(i.status===401)throw new In(`auth-required`);…let s=Nn(`token`,o);if(!s||s.code!==0||!s.data)throw new In(`relay-unavailable`);return s.data.token}…}
@6765087  function H9n(e){return[`/remote/v4`,`/remote/v4/`].includes(e.pathname)&&e.searchParams.has(`state`)&&(e.searchParams.has(`code`)||e.searchParams.has(`authCode`)||e.searchParams.has(`error`))}
@6765283  U9n=`zcode-web-remote-last-device-sid`,W9n=`zcode-web-remote-last-device-mid`
@6765795  async start(){this.reset();let e=new URL(this.options.getHref());if(H9n(e)){this.options.onView({kind:`callback`});return}if(!this.options.auth.getZCodeJwtToken()){this.options.onView({kind:`login`});return}let t=mr(e.searchParams);if(!t){this.options.onView({kind:`failure`,failure:{reason:`invalid-mobile-connection`}});return}if(!t.deviceSid){await this.openDevices(!0);return}this.connect({...t,deviceSid:t.deviceSid})}
@6768630  connect(e){this.reset();let{signal:t}=this.scope;this.options.connect({params:e,signal:t,getToken:e=>{let t=this.options.auth.getZCodeJwtToken();return t?this.options.token(t,e):Promise.reject(new In(`auth-required`))},onPaired:…,onFailure:…})}
@6769046  startLogin(e){this.options.auth.startLogin({...this.options.loginOptions,...e?{provider:e}:{},appReturnTo:this.options.getHref()})}
@6803673  function zer(e){return H9n(new URL(window.location.href))||[`/cn/share/callback`,`/share/callback`].includes(window.location.pathname)&&e.has(`state`)&&(e.has(`code`)||e.has(`authCode`)||e.has(`error`))}
@6803876  function Ber(){document.title=`ZCode - Web Remote Control Sign In`;let e=C7n(y7n(new URLSearchParams(window.location.search).get(`state`)??``)?.app_return_to);K9.render((0,$.jsx)(G5n,{authService:q9,onSuccess:({appReturnTo:e})=>{window.location.replace(e??`/remote/v4`)},onRetry:()=>{window.location.replace(e??`/remote/v4`)}}))}
@6805086  function Her(){document.title=`ZCode - Web Remote Control Sign In`,K9.render((0,$.jsx)(q5n,{onLogin:e=>Y9.startLogin(e)}))}
@6805209  var Uer={getItem:e=>window.localStorage.getItem(e),setItem:(e,t)=>window.localStorage.setItem(e,t)}
@6805309  Y9=new K9n({auth:q9,getHref:()=>window.location.href,replaceHref:e=>window.history.replaceState(window.history.state,``,e),storage:Uer,locale:G9(navigator.language),devices:E9n(window.location.origin),…token:N9n(window.location.origin),connect:e=>{ttr(e)},onView:…})
@6830751  async function ntr(){let e=new URLSearchParams(window.location.search);…if(zer(e)){Ber();return}if(g9n(window.location.pathname)){await Ver();return}if(Jer()){await Y9.start();return}…}
```

### 8.2 `Reference_web/3.16.1/assets/src-uVqBd_Eq.js`

```text
@30662   hr=`bigmodel`,gr=`ZCODE_CREDENTIAL_DECRYPT_FAILED`
@30995   br=`auth:zcode-jwt-invalid`
@31023   function xr(e,t=Date.now(),n=3e4){try{let r=e.split(`.`)[1];if(!r)return{kind:`unknown`};…let c=JSON.parse(new TextDecoder().decode(s));if(typeof c.exp!=`number`||!Number.isFinite(c.exp)||c.exp<=0)return{kind:`unknown`};let l=c.exp*1e3;return t+Math.max(0,n)>=l?{kind:`expired`,expiresAt:l}:{kind:`valid`,expiresAt:l}}catch{return{kind:`unknown`}}}
@51431   function Wa(e,t){return e.get(t)?.trim()||void 0}
@51830   function Ja(e){if(e.has(`hash`)||e.has(`t`))return null;let t={};for(let[n,r]of[[`deviceSid`,`sid`],[`deviceMid`,`mid`],[`deviceName`,`name`],[`appVersion`,`app_version`]]){let i=Wa(e,r);i&&(t[n]=i)}return t}
@53637   var io={register:{method:`POST`,path:`/api/v1/remote/device`},list:{method:`GET`,path:`/api/v1/remote/device`},delete:{method:`POST`,path:`/api/v1/remote/device/delete`},token:{method:`POST`,path:`/api/v1/remote/token`}}
@53993   oo=p({token:y().regex(/^[A-Za-z0-9_-]{21}[AQgw]$/),expires_in:m().int().positive()}),so=p({code:m().int().refine(e=>e!==0),msg:y(),data:ne().optional()})
@63079   function cs(e,t,n){let r=is.parse(e),i=r.auth.status===`ready`?{[`oauth:${r.auth.provider}:access_token`]:…,zcodejwttoken:…}:{};return`(() => {…for (const key of ["oauth:zai:access_token","oauth:bigmodel:access_token","zcodejwttoken"]) localStorage.removeItem(key);for (const [key,value] of Object.entries(${JSON.stringify(i)})) if (value) localStorage.setItem(key,value);…})()`}
@69739   …,hr as Wt,…（导出映射：Wt = hr = `bigmodel`；主包以 `Wt as Lr` 导入 → 登录页 `Lr` 即 `bigmodel`）
```

### 8.3 `Reference_web/3.16.1/assets/validation-BBf1FA1a.js`

```text
@98937   var yl={zai:`zai-api`,bigmodel:`bigmodel-api`}
@125385  …n.pathname=`/api/v1/remote/ws2`,n.toString()}…
```

> `@98937` 的 `yl` 是“模型 provider → API provider”映射，**不是** OAuth provider 枚举，勿误读。
> `@125385` 是 relay ws2 URL 构造。

### 8.4 `Reference_web/3.16.1/assets/IntlProvider-Be9akOJ8.js`

```text
@76171   连接 Z.ai 继续使用
@76228   连接 BigModel 继续使用
@76396   login.oauth.loginFailure
@76423   登录失败，请重试
@76515   login.expired.title
@76555   login.expired.description
```

（均为**桌面端登录界面**文案，`/remote/v4` 不使用。）

### 8.5 当前 Android 实现

| 文件 | 关键位置 |
|---|---|
| `E:\Zcode_android\android\app\src\main\kotlin\app\zemote\auth\OAuth.kt` | L50-74 常量；L138-160 `create`；L168-177 `isCallback`；L184-198 `parseCallback`；L201-211 `encodeState`；L267-302 `exchange`；L304-343 `parseTokenResponse`；L375-424 `RemoteTokenClient` |
| `...\ui\auth\LoginViewModel.kt` | L55-61 `start`；L64-80 `onCallback`；L102-131 `exchange` |
| `...\ui\auth\LoginScreen.kt` | L145-176 文案；L209-233 WebView；L235-261 回调拦截（双 `shouldOverrideUrlLoading` + `onPageStarted` 兜底） |
| `...\ui\auth\LoginOverlay.kt` | L15-35 全屏覆盖层 |
| `...\ui\AppContainer.kt` | L150 `terminalTokenProvider = { host, jwt -> remoteTokenClient.exchange(host, jwt).token }` |
| `...\ui\UiFailure.kt` | L28 `RemoteTokenException → message` |
| `...\data\AuthStore.kt` | L92-100 `token()`；L103-124 `save()`；L127-129 `clear()`；L164-165 键名 |

---

## 9. 未解问题

1. **`data.user` 的真实形态**（`UNVERIFIED`）：网页端 `A9` 双形态兼容说明服务端至少曾返回过两种；BigModel 与 Z.ai 是否不同、`avatar` 是 URL 还是裸 base64，均需一次真实响应才能定。直接影响 R1。
2. **`data.expires_in` 的语义**（`UNVERIFIED`）：是 OAuth access_token 的 TTL 还是 zcode JWT 的 TTL？决定 R2 该不该继续用 `expiresAt` 做硬门。可用「`expires_in` vs JWT `exp-iat`」比对。
3. **JWT 是否总带 `exp`**（`UNVERIFIED`）：`xr` 缺 `exp` 时返回 `unknown` 且不拦截，说明可能签发无 `exp` 的 JWT；若如此，Android 补 JWT 校验也无意义。
4. **`redirect_uri` 服务端白名单**（`UNVERIFIED`）：是否精确匹配、是否允许 query（网页端自己会带 `sid/mid/name`，故大概率允许或只校验 origin+path）。
5. **服务端是否校验 `state`**（`SPECULATION`）：网页端把整段 base64url JSON 透传回传，未见服务端约束；若服务端校验长度/字符集，需实测。
6. **授权页 `error` 的取值集合**（`UNVERIFIED`）：客户端不枚举，任何字符串都原样展示；`access_denied` / `invalid_state` 等具体词无法从 bundle 确认。
7. **是否要求 PKCE**（`UNVERIFIED`）：授权请求未带 `code_challenge`/`code_challenge_method`，token 请求也未带 `code_verifier`；服务端是否强制未知。
8. **BigModel 回调参数名**（`UNVERIFIED`）：客户端同时收 `code` 与 `authCode`，但无法从代码判断 BigModel 实际用哪个。
9. **`sessionStorage` 被禁用时的行为**（`UNVERIFIED`）：`savePendingNonce` 直接 `setItem`，无 try/catch；隐私模式下若抛异常，登录会在点击瞬间失败（文案为浏览器异常原文）。
10. **`At({})` 的实现**（`UNVERIFIED`，生产不可达）：仅当 `VITE_BIGMODEL_OAUTH_ORIGIN` 为空时求值。
11. **回调页 code 的暴露窗口**（`UNVERIFIED`）：服务端是否 302 到无 query 的地址、`Referer` 是否外泄，取决于部署，不在 bundle 内。
12. **`/remote/__test__` 路径**（`UNVERIFIED`）：仅出现在白名单集合，用途未在本次范围内展开。

---

## 附录 A · 对上一版偏移的更正

| 上一版 | 复算结果 | 说明 |
|---|---|---|
| `q5n @6592234` | **`@6708215`** | 上一版误用了字符串下标或其它文件坐标 |
| `Y9.start() @6648817` | **`@6765795`** | 同上 |
| `src token:{…} @53807` | **`@53637`**（`io` 表起点） | 表内 `token` 项紧随其后 |
| `src hr='bigmodel' @30609` | **`@30662`** | 差 53 字节 |
| `Ber @6803876`、`H9n @6765087`、`Z5n @6710263`、`A9 @6709691`、`F9 @6715549`、`p7n @6715765`、`r7n @6712266`、`a7n @6712321` | 一致 | 已复核 |

## 附录 B · 范围声明

上一版含 `app.asar` 桌面端对照（deep link `zcode://oauth/callback`、`/api/v1/oauth/cli/*` 轮询流、refresh_token 等）。本轮硬约束只允许 `Reference_web/3.16.1/` 与项目源码，**未复核该部分**，故不将其作为 3.16.1 网页事实继承；如需桌面端结论，应在允许 asar 的信源下单独审计并标注坐标。
