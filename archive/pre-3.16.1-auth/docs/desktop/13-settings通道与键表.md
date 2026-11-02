# 13 · `setting` / `provider-settings` / `model-selection` / 控制器通道权威文档

> **审计基线**：ZCode 桌面端 3.15.3（`app.asar` 340,516,766 B，build `d7ca8bee`，2026-10-13）
>
> **证据口径**
> - `hi:` = `out/host/index.js`（1,681,899 B）
> - `ou:` = `out/host/chunk-OUCOFHX4.js`（823,914 B，设置 zod schema 与 Provider 配置模型）
> - `t2:` = `out/host/chunk-T2GB4I3K.js`（135,089 B，通道描述符 / Provider 设置门面 / 控制器帧 schema）
> - `wm:` = `out/host/chunk-WNM2RERY.js`（18,099 B，通道 RPC 框架）
> - `web:` = `Reference_web/3.15.3/assets/index-BGa3KiRe.js`（6,592,643 B，网页/桌面共用 UI 包）
> - 所有偏移均为**该文件内容的 UTF-8 字节偏移**（由 `Buffer.byteLength(text.slice(0,i))` 计算；网页包同样按 UTF-8 字节计）。
> - 只读：未执行包内任何代码，未修改任何源码。
> - 本文只写静态可证事实；无法静态证实的一律标 **UNKNOWN**。

---

## 0. 结论摘要（先看这 10 条）

1. **`setting` 通道只有 5 个成员**：`get`、`update`、`updateDataBaseDir`、`ensureDefaultProject`、`onDidUpdate`（事件）。
   服务对象是 `createObservableSettingService(createSettingService())`，注册于 `hi:1,541,460` 的 `.register(Ws,s)`，
   描述符 `Ws = f(h.Setting)`（`t2:80,468`，即 `{channelName:"setting"}`）。
2. **`get()` 的权威 schema 是 54 个键**（`ou:612,218` 的 `jM`），由 `LM = e.preprocess(<迁移链>, jM)` 导出为 `lu`
   （`ou:614,859` / 导出表 `ou:821,255`）。`lu.parse({})` 即默认设置（`hi:250,973` 的 `defaultSettings`）。
3. **`update(patch, expected?)` 是浅合并**：`{...current, ...QS.parse(iW(patch))}` 再走 `lu.parse`（默认值回填 + 迁移）；
   `expected` **只**用于比较 `providerFamilyDomain` 与 `providerFamilyConnectionSelections` 两个字段，
   不一致抛 `Error("Account connection settings changed")`（`hi:254,027`）。
4. **`onDidUpdate` 的负载是 `{keys: string[]}`**，`keys` 取自**调用方原始 patch 的键**（不是解析后的 patch），
   见 `hi:1,456,694` 的 `createObservableSettingService`。
5. **`provider-settings` 共 14 个可调用方法 + 1 个事件**（`t2:72,428` `Vg`）：`getView / refresh /
   createPersonalProvider / resolveModelConfig / savePersonalProviderOverlay / deletePersonalProvider /
   reorderPersonalProviders / reorderPersonalModels / addPersonalModel / renamePersonalModel /
   deletePersonalModel / savePersonalModelDraft / setPersonalModelEnabled / testModelConnectivity` + `onDidChange`。
6. **`model-selection` 只有 3 个成员**（`t2:74,638` `Gg`）：`getView(request?)`、`onDidChange`、`dispose`。
7. **`provider-provisioning-target` 只有 1 个方法 `apply(payload)`**（`hi:1,044,496`），严格 schema 在 `ou:575,557`；
   注册是条件式的（`serviceAuthorityMode==="desktop-attached-remote"` 或 `providerProvisioningTargetEnabled===true`）。
8. **`window-controller.mutateTask` 的 mutation 是 9 元判别联合**：`pin / archive / delete / delete-archived /
   delete-archived-batch / mark-read / mark-unread / open / resume`（`hi:1,626,185` 的 `switch`）。
   `address` 为 `{remoteSessionId?, workspacePath, workspaceIdentity?, taskId}`（`t2:53,519`）。
9. **控制器帧有严格的 zod schema**（`t2:53,441` 起）：topic 只有 `controller/tasks-index` 与
   `controller/workspaces`；快照帧 `fromSeq` 必须为 0；任务行 = `{address,meta,membership,sourceAvailability,
   liveStatus,activity?,searchSnippets?}`。
10. **远程桥对设置没有任何键级限制**：`setting` 通道未被 `exposeServicesOnMessagePort` 覆盖
    （`hi:1,664,910`），`update` 内部也没有白名单。所有 54 键远程都能写；是否“有意义”取决于桌面进程是否消费该键（§6）。

---

## 1. `setting` 通道

### 1.1 服务对象与注册

| 项 | 内容 | 坐标 / 原文 |
|---|---|---|
| 通道名 | `setting` | `t2:80,468`：`var xt=f(h.Setting)`；导出为 `Ra`（`t2` 导出表），host 侧别名 `Ws` |
| 注册 | `Vt.register(Ws,s)` | `hi:1,541,460` |
| 服务装配 | `let o=e?.settingService?null:Ed(), s=AY(e?.settingService??o.service)` | `hi:1,520,109` |
| 基础服务工厂 | `function Ed()`（内部名 `createSettingServiceWithMigrations`） | `hi:253,497` |
| 可观察包装 | `function AY(e)`（`createObservableSettingService`） | `hi:1,456,694` |
| 设置文件 | `join(ZCODE_DESKTOP_HOME_DIR \|\| HOME \|\| USERPROFILE, ".zcode", "v2", "setting.json")` | `hi:250,815` `uW()` / `hi:250,883` `Rp()` |

服务对象（`hi:253,497` 起，`s={...}`）：

```js
s = {
  async get() {...},                       // 返回完整设置对象
  async update(d, l) {...},                // d=patch, l=expected
  async updateDataBaseDir(d) {...},
  async ensureDefaultProject(d) {...}
}
// AY 包装后追加：
onDidUpdate(n) { t.add(n); return () => t.delete(n) }
// 并覆写 update：先 await e.update(n,r)，再向监听者发 {keys:Object.keys(n)}
```

`fromService` 的可见性规则（`wm:17,059` / `wm:16,892`）：`on[A-Z]` 只能 `listen`，其余函数只能 `call`。
因此线上方法集 = **`get` / `update` / `updateDataBaseDir` / `ensureDefaultProject`（call）+ `onDidUpdate`（listen）**。

> 网页包中的“无桥桩实现”也印证了这 4 个 callable：
> `web:6,566,648`：`t={get:async()=>({recentProjects:[],locale:...}),update:async()=>{},updateDataBaseDir:async()=>{},ensureDefaultProject:async e=>({path:e,created:!1})}`。

### 1.2 方法逐条

| 方法 | 线格式入参 | 返回 | 说明 |
|---|---|---|---|
| `get` | `[]` | 54 键设置对象（全部默认值已回填） | 先等待进行中的写入，再读文件；若检测到需要持久化迁移，会先写回再重读（`hi:253,497` 的 `async get()`） |
| `update` | `[patch]` 或 `[patch, expected]` | `undefined` | 浅合并 + 默认值回填 + 原子写；见 §1.5 |
| `updateDataBaseDir` | `[dir?]` | `undefined` | 校验目录 → 复制旧数据目录到新目录 → `this.update({dataBaseDir:dir})`（`hi:253,497`） |
| `ensureDefaultProject` | `[dir]` | `{path, created}` | 在 `dir/ZCodeProject` 上建目录；`created` 表示是否新建（`pre="ZCodeProject"`，`hi:250,435`） |
| `onDidUpdate` | `listen`，事件负载 `{keys}` | 内部返回退订函数 | 见 §1.6 |

> **线格式关键点**：`fromService.call` 是 `m.apply(i, h||[])`（`wm` §1.6），
> 所以 RPC 的 `arg` 必须是**位置参数数组**。单对象形参的方法要发 `[{...}]`，不能直接发 `{...}`。

### 1.3 `get()` 返回的完整 schema（54 键，逐列）

**权威对象**：`ou:612,218` `var jM=e.object({...})`；外层 `ou:614,859`：

```js
LM = e.preprocess(t => OM(AM(MM(zM($M(EM(NM(UM(t)))))))), jM)
```

迁移链（从内到外）：
`UM` 强制 `enabledBuiltinAgentCliProviders=[...is]`；`NM` 把 `lastOpenTabs/remoteWorkspaceHistory` 迁到 `lastWorkspaceSession`；
`EM` 清洗 `zcodeEndpointOrigin`；`$M` 把旧 `locale` 迁到 `localePreference`；`zM` 迁移 `closeToTrayOnWindows`；
`MM` 迁移 `messageStreamShowReasoning`；`AM` 清洗 `desktopWindowSize`；`OM` 清洗 `embeddedBrowserViewportPreference`。

类型别名（同文件）：
`F = string().trim().min(1)`（`ou:606,064`）；`Wr = enum("zh-CN","en-US")`（`ou:606,091`）；
`bx = enum("system","zh-CN","en-US")`（`ou:606,120`）；`Sx = enum("queue","guide")`（`ou:606,158`）；
`go = ["disabled","onDemand","alwaysOn"]`（`ou:598,085`）；`TM = enum(go).optional().catch(undefined)`（`ou:606,187`）；
`_x = int().min(-3).max(5)`（`ou:606,258`）；`eh = {width:int>=480, height:int>=640, maximized:boolean}`（`ou:606,293`）；
`Es = discriminatedUnion("mode",[{mode:"auto"},{mode:"shell",dialect:"cmd"|"git-bash",id:F,label:F,path:F}])`（`ou:606,395`）；
`xx = array(enum("claude","opencode","gemini","codex","glm")).transform(()=>["glm"])`（`ou:606,555`，`is=[Tr]`、`Tr="glm"` @ `ou:475,835` / `ou:475,732`）；
`yx = enum(14 值)`（`ou:605,886`）。

| # | 键 | 类型 / 约束 | 默认值 | 可选 |
|---:|---|---|---|---|
| 1 | `recentProjects` | `array(string)` | `[]` | 否 |
| 2 | `locale` | `enum("zh-CN","en-US")` | `"zh-CN"` | 否 |
| 3 | `shortcutBindings` | `record(string, array(string))` | — | 是 |
| 4 | `localePreference` | `enum("system","zh-CN","en-US")` | `"system"` | 否 |
| 5 | `terminalInheritSystemProfile` | `boolean` | `true` | 否 |
| 6 | `terminalFontFamily` | `string.trim().min(1)` | — | 是 |
| 7 | `integratedTerminalShell` | `{mode:"auto"} \| {mode:"shell",dialect:"cmd"\|"git-bash",id,label,path}` | — | 是 |
| 8 | `httpProxy` | `string.trim().min(1)` | — | 是 |
| 9 | `httpProxyNoProxy` | `string.trim().min(1)` | — | 是 |
| 10 | `httpProxyCaCertPath` | `string.trim().min(1)` | — | 是 |
| 11 | `embeddedBrowserAllowInsecureCertificates` | `boolean` | `false` | 否 |
| 12 | `embeddedBrowserViewportPreference` | `{mode:"normal"\|"responsive", viewport:{width,height}, zoom:"fit"\|"50"\|"75"\|"100"\|"125"\|"150"\|"200"}`（strict） | `{mode:"normal",viewport:{width:393,height:852},zoom:"fit"}` | 否 |
| 13 | `computerUseComposerEntryHidden` | `boolean` | `true` | 否 |
| 14 | `taskAutoArchiveEnabled` | `boolean` | `false` | 否 |
| 15 | `taskAutoArchiveOlderThanDays` | `int().positive().max(365)` | `7` | 否 |
| 16 | `closeToTrayOnWindows` | `boolean` | `true` | 否 |
| 17 | `closeToTrayOnWindowsMigrationInitialized` | `boolean` | `true` | 否 |
| 18 | `closeToTrayLinuxMigrationInitialized` | `boolean` | `false` | 否 |
| 19 | `keepAwakeWhileRunning` | `boolean` | `false` | 否 |
| 20 | `desktopZoomLevel` | `int().min(-3).max(5)` | — | 是 |
| 21 | `desktopWindowSize` | `{width:int>=480, height:int>=640, maximized:boolean}` | — | 是 |
| 22 | `desktopChromiumHardwareAccelerationEnabled` | `boolean` | `true` | 否 |
| 23 | `messageStreamShowReasoning` | `boolean` | `true` | 否 |
| 24 | `messageStreamShowReasoningMigrationInitialized` | `boolean` | `true` | 否 |
| 25 | `messageStreamShowTodos` | `boolean` | `false` | 否 |
| 26 | `toolGroupingExploreEnabled` | `boolean` | `true` | 否 |
| 27 | `toolGroupingTerminalEnabled` | `boolean` | `true` | 否 |
| 28 | `toolGroupingChangesEnabled` | `boolean` | `false` | 否 |
| 29 | `zcodeInteractionBehavior` | `enum("queue","guide")` | `"queue"` | 否 |
| 30 | `askUserQuestionAutoResolutionEnabled` | `boolean` | `true` | 否 |
| 31 | `modelIoFullRetentionEnabled` | `boolean` | `false` | 否 |
| 32 | `dynamicWorkflowMode` | `enum("disabled","onDemand","alwaysOn").optional().catch(undefined)` | `undefined` | 是 |
| 33 | `enabledBuiltinAgentCliProviders` | `array(enum(...)).transform(()=>["glm"])` | `["glm"]` | 否（解析后恒为 `["glm"]`） |
| 34 | `startPlanRecommendationDismissed` | `boolean` | `false` | 否 |
| 35 | `providerFamilyConnectionSelections` | `{zai?:M_, bigmodel?:M_}.partial()` | `{}` | 否 |
| 36 | `providerFamilyDomain` | `enum("zai","bigmodel")` | — | 是 |
| 37 | `providerFamilyDomainUpdatedAt` | `int().nonnegative()` | — | 是 |
| 38 | `providerFamilyDomainMigrated` | `boolean` | `false` | 否 |
| 39 | `nativeSearchEnhancementsEnabled` | `boolean` | `true` | 否 |
| 40 | `onboardingOccupation` | `enum(14 值).nullish()` | — | 是（可为 `null`） |
| 41 | `proactiveSuggestionsEnabled` | `boolean` | — | 是 |
| 42 | `memoryEnabled` | `boolean` | `false` | 否 |
| 43 | `lastWorkspaceSession` | `array(Cx)` | `[]` | 否 |
| 44 | `lastActiveTabIndex` | `int().nonnegative()` | `0` | 否 |
| 45 | `lastActiveTaskByWorkspace` | `record(string,string)` | — | 是 |
| 46 | `dataBaseDir` | `string.trim().min(1)` | — | 是 |
| 47 | `pendingPostUpdateReleaseNotes` | `th` | — | 是 |
| 48 | `receivePreviewUpdates` | `boolean` | `false` | 否 |
| 49 | `autoDownloadAndInstallUpdates` | `boolean` | `false` | 否 |
| 50 | `skippedElectronUpdateVersions` | `partialRecord(enum("stable","preview"), string)` | `{}` | 否 |
| 51 | `settingsSyncFirstRunPromptHandled` | `boolean` | — | 是 |
| 52 | `webRemoteControlExternalRelayDevice` | `{deviceSid:F}` | — | 是 |
| 53 | `webRemoteControlLastEnabledContext` | `{workspacePath:F, workspaceIdentity?:F, initialTaskId?:F}` | — | 是 |
| 54 | `zcodeEndpointOrigin` | `preprocess(string→URL字符串, string.optional())` | — | 是 |

**重要**：`jM` 是普通 `e.object(...)`（非 `.strict()`）。Zod 默认 **strip** 未知键，因此：
文件里的未知键不会出现在 `get()` 结果里；`update` 的未知键被静默丢弃（不报错）。

### 1.4 子 schema 展开（原文）

**`integratedTerminalShell`**（`ou:606,395`）：

```js
Es=e.discriminatedUnion("mode",[
  e.object({mode:e.literal("auto")}),
  e.object({mode:e.literal("shell"),dialect:e.enum(["cmd","git-bash"]),id:F,label:F,path:F})
])
```

**`embeddedBrowserViewportPreference`**（`ou:557,076` / 默认值 `ou:557,157`）：

```js
bs=e.object({mode:e.enum(["normal","responsive"]),viewport:ys,zoom:CO}).strict()
CO=e.enum(["fit","50","75","100","125","150","200"])
R_={mode:"normal",viewport:{width:393,height:852},zoom:"fit"}
```

**`desktopWindowSize` / `desktopZoomLevel`**（`ou:606,258` / `ou:606,293`）：

```js
_x=e.number().int().min(-3).max(5)
eh=e.object({width:e.number().int().min(480),height:e.number().int().min(640),maximized:e.boolean()})
```

**`lastWorkspaceSession` 元素 `Cx`**（`ou:607,587`）：

```js
Cx=e.discriminatedUnion("kind",[
  e.object({kind:"local", workspacePath:F, workspacePurpose:e.enum(["project","conversation"]).default("project")}),
  e.object({kind:"remote", workspacePath:F, localWorkspacePath:F.optional(), workspaceIdentity:F.optional(),
            target:Ix, lastOpenedAt:int>=0, lastConnectionStatus:enum("connected","failed"),
            lastConnectionError:string.optional()})
])
```

**`Ix`（远程 target）**（`ou:606,862`）：

```js
ssh:   {kind:"ssh", host:F, port?:int 1..65535, username:F, sshConfigAlias?:F, privateKeyPath?:string,
        assetInstallMode?:enum(_s), resourcePackages?:{selectedPackageIds?:string[]},
        passwordCredentialKey?:F, privateKeyPassphraseCredentialKey?:F}
wsl:   {kind:"wsl", distro?:string, user?:string}
docker:{kind:"docker", container:F}
server:{kind:"server", url:url, name?:F, workspacePath?:string, serverId?:F, tokenCredentialKey?:F}
```

**`pendingPostUpdateReleaseNotes`（`th`）**（`ou:606,673`）：

```js
th=e.object({version:F,title:F,markdown:F,releaseDate:F.optional(),
             releaseNotesByLocale:e.partialRecord(Wr,e.object({title:F,markdown:F})).optional()})
```

**`providerFamilyConnectionSelections`（`Dr`）与 `M_`**（`ou:574,693` / `ou:574,447`）：

```js
zf=e.string().trim().min(1)
M_=e.discriminatedUnion("kind",[
  e.object({kind:"start-plan"}).strict(),
  e.object({kind:"individual-coding-plan"}).strict(),
  e.object({kind:"team-coding-plan",productId:zf,organizationId:zf,projectId:zf}).strict()
])
Dr=e.object({zai:M_.optional(),bigmodel:M_.optional()}).partial()
```

**`skippedElectronUpdateVersions`（`PM`）**（`ou:606,825`）：`e.partialRecord(enum("stable","preview"), F).default({})`。

**`webRemoteControl*`**（`ou:608,008` / `ou:608,035`）：

```js
Rx=e.object({deviceSid:F})
wx=e.object({workspacePath:F, workspaceIdentity:F.optional(), initialTaskId:F.optional()})
```

**`zcodeEndpointOrigin`（`nh`）**（`ou:608,124`）：`preprocess(字符串→`new URL()`字符串；失败则丢弃, string().optional())`。

### 1.5 `update(patch, expected?)` 的合并语义（逐行）

原文（`hi:254,027`）：

```js
async update(d,l){await o(i(async(p,m)=>{
  let h=QS.parse(iW(d)), v=await cW();
  if(l){
    let k=QS.parse(l);
    if(v.providerFamilyDomain!==k.providerFamilyDomain ||
       JSON.stringify(v.providerFamilyConnectionSelections??{})!==
       JSON.stringify(k.providerFamilyConnectionSelections??{}))
      throw new Error("Account connection settings changed")
  }
  let P=lu.parse({...v,...h});
  P.recentProjects&&(P.recentProjects=[...new Set(P.recentProjects)].slice(0,ure));
  await SI(P,p,r,m,Object.hasOwn(d,"providerFamilyConnectionSelections"))
},"runUpdate"))}
```

其中：
- `QS` = `ZM`（`ou:614,916`）：**54 键的 patch schema，全部 `.optional()`**（未知键 strip）。
  与 `jM` 的差异只有 4 处：
  - `dynamicWorkflowMode: union([enum(go), literal("")]).optional()`（`""` 表示清除）
  - `providerFamilyDomain: union([enum("zai","bigmodel"), literal("")]).optional()`
  - `onboardingOccupation: enum(14 值).nullish()`
  - `skippedElectronUpdateVersions: partialRecord(enum("stable","preview"),F).optional()`
- `iW`（`hi:246,540`）在解析前做归一化：
  - 有 `locale` 无 `localePreference` → 把 `locale` 复制为 `localePreference`；
  - `terminalFontFamily` / `httpProxy` / `httpProxyNoProxy` / `httpProxyCaCertPath` 去空白，空串 → `undefined`；
  - `integratedTerminalShell.mode==="auto"` → `undefined`；`mode==="shell"` 时 trim `id/label/path`；
  - `zcodeEndpointOrigin` 做 URL 规范化（见 `iW` 后续分支）。
- 合并是**一层浅合并**：嵌套对象（如 `embeddedBrowserViewportPreference`、`desktopWindowSize`）整体替换，不做深合并。
- 合并后再跑 `lu.parse`：`.default()` 键在值为 `undefined` 时会**回填默认值**（不是删除）；
  `.optional()` 键在值为 `undefined` 时变为缺失。
- `recentProjects` 去重并截断到 **10** 条（`ure=10`，`hi:250,435`）。
- `expected` 是“部分 CAS”：只比较 2 个账号字段，其余字段变化不会导致冲突。
  若当前 `providerFamilyDomain` 已设置，传 `{}` 会因 `"zai" !== undefined` 抛冲突；
  仅当两个字段都未设置时 `{}` 才通过。
- 第 5 个实参 `Object.hasOwn(d,"providerFamilyConnectionSelections")` 传给 `SI`，
  含义是“本次 patch 显式写了账号连接选择”，写盘时不要删掉该字段（`SI` 的 `!o&&bp(c).length>0&&delete l.providerFamilyConnectionSelections`）。

**写盘 `SI`**（`hi:252,783`）：

```js
let c=await _p(a), l={...ay(c),...e};       // ay() 保留旧文件里的 legacy 账号字段
!o&&bp(c).length>0&&delete l.providerFamilyConnectionSelections;
await ao(a, JSON.stringify(l,null,2), {beforeRename, runRename})   // 原子 rename
```

- 写入串行化：`o(...)` 进入 per-service 队列（`enqueueSettingsWrite`），并用 `m()`（`shouldCommit`）做**代际校验**：
  期间有更新则丢弃旧写。
- `get()` 会 `await e`（等待最后一个写完成）后再读。

### 1.6 `onDidUpdate` 事件

原文（`hi:1,456,694`）：

```js
function AY(e){let t=new Set;return{...e,
  onDidUpdate(n){return t.add(n),()=>t.delete(n)},
  async update(n,r){r?await e.update(n,r):await e.update(n);
    let o=Object.freeze(Object.keys(n));
    if(o.length===0)return;
    let s=Object.freeze({keys:o});
    for(let a of t)a(s)}}}
```

| 项 | 事实 |
|---|---|
| 事件名 | `onDidUpdate`（`on[A-Z]` → 只能 `listen`） |
| 负载 | `{keys: string[]}`（`Object.freeze`） |
| `keys` 来源 | **调用方传入的原始 patch**（`n`），不是 `QS.parse` 后的对象。因此未知键也会出现在 `keys` 里，但不会被持久化 |
| 触发时机 | `update` 成功返回后；`update` 抛错则不触发 |
| 空 patch | `keys.length===0` 直接 return，不触发 |
| 退订 | 服务层返回的是**函数** `()=>t.delete(n)`，不是 `{dispose}`。通道层的 `ee` 包装器在“最后一个监听者移除”时执行 `s?.dispose()`（`wm:17,672`），与函数形状不匹配 → **可能抛 TypeError**（未实测，见 §7） |
| 网页端用法 | 网页 UI **不使用** `onDidUpdate`：它在 `update` 后主动重读，并监听 host 的 `onSettingsChanged`（`web:1,436,199` `uk()`、`web:1,435,831` `Drt`） |

### 1.7 持久化与容错（`hi:250,883` 起）

| 场景 | 行为 |
|---|---|
| 文件不存在 `ENOENT` | 返回默认设置（`lu.parse({})`），不写盘 |
| JSON 解析失败 | 重试 3 次、间隔 300 ms；仍失败 → 把文件改名为 `setting.json.corrupt-<ISO时间>`，返回默认值 |
| 文件 JSON 合法但 schema 校验失败 | **直接返回默认值**，不备份、不覆盖原文件 |
| 需要迁移（`needsMigrationPersist`） | `get()` 内写回迁移结果后再重读 |
| 写盘失败 | 抛出；调用方（RPC）得到 202 |
| 迁移判定 `vre()` | 旧账号字段存在 / 两个 migration 标记不为 true |

---

## 2. `provider-settings` 通道

### 2.1 注册与方法全集

| 项 | 内容 | 坐标 |
|---|---|---|
| 描述符 | `kt=f(h.ProviderSettings)` → `{channelName:"provider-settings"}` | `t2:72,376` |
| 门面工厂 | `function Vg(t,o,r)`（`createProviderSettingsService`） | `t2:72,428`（方法体 72,437 起） |
| 底层实现 | `_h` = `ProviderSettingsFacade`（`hi:79,392`） | `hi:79,392` |
| 注册 | `Vt.register(pd,nt.providerSettings)` | `hi:1,545,903` / `hi:1,545,936`（`hi:1,541,460` 链尾） |

**方法全集（14 callable + 1 event）**，`Vg` 原文（`t2:72,437`）：

| # | 方法 | 位置参数（RPC args 数组） | 返回 |
|---:|---|---|---|
| 1 | `onDidChange` | `[listener]`（listen） | 订阅对象（内部 `{dispose}`） |
| 2 | `getView` | `[]` | Provider 设置视图（§2.2） |
| 3 | `refresh` | `[reason:string]` | 视图（网页传 `"settings-manual"`，`web:5,763,914`） |
| 4 | `createPersonalProvider` | `[opts:{templateId?,providerName?,locale?,initialConfig?}]` | `{providerId, view}` |
| 5 | `resolveModelConfig` | `[req:{providerId,modelId,personalConfig?,originalModelId?}]` | `{inheritedConfig, effectiveConfig, issues[]}` |
| 6 | `savePersonalProviderOverlay` | `[providerId, overlay, meta?]` | 视图 |
| 7 | `deletePersonalProvider` | `[providerId]` | 视图 |
| 8 | `reorderPersonalProviders` | `[providerIds:string[]]` | 视图 |
| 9 | `reorderPersonalModels` | `[providerId, modelIds:string[]]` | 视图 |
| 10 | `addPersonalModel` | `[providerId, modelId, modelConfig, options?]` | 视图 |
| 11 | `renamePersonalModel` | `[providerId, modelId, nextModelId]` | 视图 |
| 12 | `deletePersonalModel` | `[providerId, modelId]` | 视图 |
| 13 | `savePersonalModelDraft` | `[draft:{providerId,originalModelId,nextModelId,personalConfig,basedOnRevision,useRecommendedConfig?}]` | 视图 |
| 14 | `setPersonalModelEnabled` | `[providerId, modelId, enabled:boolean]` | 视图 |
| 15 | `testModelConnectivity` | `[req:{workspacePath,workspaceIdentity?,providerId,modelId}]` | `undefined` 或 `{success:false,error:{code,message}}` |

**入参细节（门面 `_h`，`hi:79,392` 起）**：

- `createPersonalProvider(t)`：
  ```js
  t = { templateId?, providerName?, locale?, initialConfig? }
  // initialConfig 不能含 group / builtinModelIds（底层显式抛错）
  → { providerId, view }
  ```
  底层 `createPersonalProvider`（`hi:61,718`）会从模板继承配置、生成唯一 `providerId` 与显示名。
- `resolveModelConfig(t)`：
  ```js
  t = { providerId, modelId, personalConfig?, originalModelId? }
  // 无 personalConfig：返回继承后的 inheritedConfig / effectiveConfig
  // 有 personalConfig：把 personalConfig 作为 personalExact 合并后再算 effectiveConfig
  → { inheritedConfig, effectiveConfig, issues: [{code,path,message}] }
  ```
- `savePersonalProviderOverlay(providerId, overlay, meta?)`：
  ```js
  overlay = ProviderConfig JSON（不含 builtinModelIds/personalModelIds/modelOrder，网页端会先剥掉）
  meta = { templateId?, enabled?:boolean, providerName?:string|null }
  ```
  网页端实际调用（`web:5,763,193`）：
  `savePersonalProviderOverlay(providerId, structuredClone(rest), {providerName?, enabled?})`。
- `addPersonalModel(providerId, modelId, modelConfig, options?)`：
  `modelConfig` 是模型配置 JSON（`ui` schema 解析，`hi:52,192` `Sh`）；`options` 原样传给仓库的 `setExact`，形状 **UNKNOWN**。
- `savePersonalModelDraft(draft)`：
  ```js
  draft = {
    providerId, originalModelId, nextModelId,
    personalConfig,            // 模型配置 JSON
    basedOnRevision,           // 必须是 getView().revision（数字）
    useRecommendedConfig?      // boolean
  }
  ```
  版本冲突：`Error("Provider Settings revision conflict: expected X, current Y")`。
- `testModelConnectivity(req)`：
  先等 `waitForProviderOperations(providerId)`，再做本地可用性判定：
  `provider-unavailable` / `model-unavailable`；可用则委托给 `zcode-agent.testModelConnectivity`。

### 2.2 `getView()` 返回形状（`hi:85,362` `AQ`）

```js
{
  revision: number,
  providerTemplates: [{ templateId, templateNameMap, config }],
  providerOrder: [providerId],            // 仅 group==="standard-personal"
  providers: [{
    providerId, providerName, templateId, enabled,
    accountState?: { availability, entitled?, unavailableReason?, current?, connectionKey, effectiveAt? },
    executable: boolean,
    templateConfig?,                       // 模板配置 JSON
    effectiveBuiltinConfig?,               // 内置基线配置 JSON
    personalConfig?,                       // 个人覆盖 JSON
    effectiveConfig,                       // 最终生效 Provider 配置 JSON
    issues: [{ code, path, message }],
    models: [{
      kind: "candidate",
      modelId,
      builtin: boolean,                    // source==="builtin"
      effectiveBuiltinConfig,
      personalExactConfig?,
      useRecommendedConfig?,               // 规则类型 !== "manual-provider-model"
      effectiveConfig,                     // 模型配置 JSON
      enabled, executable, selectable,
      issues: [{ code, path, message }]
    }]
  }]
}
```

`providerTemplates[].templateNameMap` 的内部形状 **UNKNOWN**（只知来自 `Zs` 的 `templateNameMap`）。

**Provider 配置 JSON**（`hi:70,148` `vF`；schema 见 `hi:33,313`）：

```js
{
  group: "standard-personal" | "zai-family" | "bigmodel-family" | ...,
  logo?, 
  access:
    {type:"api-key"|"zhipu-coding-plan-api-key", apiKey?:string|null, apiKeyManagementUrl?:url|null}
  | {type:"zhipu-account", accountType:"zai"|"bigmodel",
     mode:"start-plan"|"individual-coding-plan"|"team-coding-plan"|"off-peak"|"highspeed",
     entitled:boolean},
  api: {type, baseUrl:url, headers?:Record<string,string>|null},
  builtinModelIds?: string[],
  personalModelIds?: string[],
  modelOrder?: string[],
  visibility?: "visible"|"hidden"
}
```

**模型配置 JSON**（序列化器 `hi:70,888` `kF`；schema `ou:595,649`（`ws`）/ `ou:596,040`（`ux`））：

```js
{
  enabled: boolean,
  properties: {
    requiresMfjsToolSchema, contextWindow,
    inputFormat: {supportsText,supportsImage,supportsVideo,supportsAudio,supportsPdf},
    outputFormat: {supportsText},
    supportsToolCall, supportsJsonSchemaOutput,
    supportsNativeWebSearch, supportsMidConversationSystem
  },
  optionSpecs: {
    reasoningLevel: {values: [...], default?, ...},   // 枚举型
    maxOutputTokens: {max, map?}                       // 上限型
  }
}
```

### 2.3 写操作的约束与错误（底层 `ProviderConfigService`，`hi:59,511`–`hi:70,000`）

| 条件 | 错误消息（原文中文） |
|---|---|
| Provider id 为空 | `providerId 不能为空` |
| 写入时 membership revision 过期 | `Provider Settings membership revision conflict` |
| Account Provider 被禁用 | `Account Provider 不允许禁用: <id>` |
| 改写 Account Provider 的 access | `固定 Account Provider 的 Access 只能由 ZCode Built-in Config 声明: <id>` |
| 个人 Provider 不存在 | `Personal Provider 尚未创建: <id>` |
| 非 standard-personal 组 | `Personal-only Provider 必须使用 standard-personal group` |
| Provider 重名 | `Provider 名称已存在: <name>` |
| 模型已存在 | `Model 已存在: <provider>/<model>` |
| 内置模型不可改名/删除 | `Built-in Model 不能重命名/不能删除: <provider>/<model>` |
| 草稿 revision 冲突 | `Personal Provider Config revision conflict: expected X, current Y` |
| `enabled` 非布尔 | `Model enabled 必须是 boolean` |

所有写操作最终都会 `refresh("settings:<op>")` 后返回新的完整视图。

---

## 3. `model-selection` 与 `provider-provisioning-target`

### 3.1 `model-selection`

| 项 | 内容 | 坐标 |
|---|---|---|
| 描述符 | `Rt=f(h.ModelSelection)` | `t2:72,404` |
| 门面工厂 | `function Gg(t,o=async()=>{},r)`（`createModelSelectionService`） | `t2:74,638` |
| 底层实现 | `Rh` = `ModelSelectionFacade` | `hi:83,958` |
| 注册 | `Vt.register(gi,nt.modelSelection)` | `hi:1,541,460` 链尾 |

**成员（3 个）**：

| 方法 | 位置参数 | 返回 |
|---|---|---|
| `getView` | `[request?]`，`request = {selection:{providerId,modelId,options?:{reasoningLevel}}}` | 视图（见下） |
| `onDidChange` | `[listener]`（listen） | `{dispose}` |
| `dispose` | `[]` | `undefined` |

> 门面 `Gg` 的 `getView` 形参是 `(S)`，内部调用 `t.getView(R, i, S)`：
> `R` = 由 `configuredDefault` 源读出的首选 selection，`i` = 门面内部维护的“已见最大 revision”，`S` = 客户端传入的唯一位置参数 `request`。
> 因此 RPC 只有一个位置参数：`{selection:{...}}`（网页端 `web:3,938,819` 的 `{selection:Oe}` 可证）。
> `getView` 不接受客户端指定 revision；返回的 `revision` 是内部计数器 `i` 与视图 revision 的较大者。

**返回形状**（`hi:83,958` `Rh.getView`）：

```js
{
  revision: number,
  providers: [{
    providerId, providerName, templateId,
    config: <Provider 配置 JSON，同 §2.2 vF>,
    models: [{ modelId, config: <模型配置 JSON，同 §2.2 kF> }]
  }],                                        // 过滤 visibility==="hidden"
  preferredSelection?: {providerId, modelId, options?:{reasoningLevel}},
  effectiveSelection?: {providerId, modelId, options?:{reasoningLevel}},  // 仅当传入 request
  selectionIssue?: "selection-missing"|"provider-not-found"|"model-not-found"
                  |"account-connection-unavailable"|"reasoning-level-missing"
                  |"reasoning-level-not-supported"                       // 仅当传入 request
}
```

### 3.2 `provider-provisioning-target`

| 项 | 内容 | 坐标 |
|---|---|---|
| 描述符 | `sL`（`ob as sL`，来自 `t2` 导出） | `hi` 导入表 |
| 工厂 | `function lT(e)`（`createProviderProvisioningTarget`） | `hi:1,044,496` |
| 注册 | `(t||e.providerProvisioningTargetEnabled===!0)&&Vt.register(sL,lT({...}))`，其中 `t=e?.serviceAuthorityMode==="desktop-attached-remote"` | `hi:1,545,974`（`hi:1,519,853` 定义 `t`） |
| 方法 | **仅 `apply(payload)`** | `hi:1,044,496` |

**`apply` 入参 schema**（`lg` = `hY`，`ou:575,557`；子 schema `ou:575,083` / `ou:575,379` / `ou:575,500`）：

```js
{
  schemaVersion: 1 | 2,
  syncId: string.trim().min(1),
  personalConfig: {
    providerConfigRules: { providerRules: unknown[] },
    modelConfigRules: { providerModelRules: unknown[], manualProviderModelRules: unknown[] },
    providerOrder?: string[],
    defaultModelSelection?: <selection>
  },
  accountSettings: {
    providerFamilyDomain: "zai" | "bigmodel" | null,
    providerFamilyConnectionSelections: Dr
  },
  credentials: [{ scope:"oauth-session"|"account-provider", key:string, value:string }]  // max 256
}
```

约束：`schemaVersion===2` 时所有 credentials 的 `scope` 必须是 `oauth-session`。

**返回**（`hi:1,044,496`）：

```js
{syncId, status:"applied", personalProviderCount, credentialCount, configRevision, rolledBack:false}
{syncId, status:"already-applied", ...}          // 同一 syncId 已应用
{syncId, status:"failed", ..., errorMessage, errorCode, rolledBack:true}
{syncId, status:"rollback_failed", ..., errorMessage, errorCode, rolledBack:false}
```

副作用顺序：写 settings（`providerFamilyDomain` + `providerFamilyConnectionSelections`）→ 写/删凭据 →
写 personal provider 配置 → `accountProviderSource.refresh` → `providerRuntime.registryService.refresh` →
落盘 `runtime/provider/provisioning.json`。失败会按已写内容回滚。

---

## 4. `window-controller`：`mutateTask` 与控制器帧

### 4.1 通道服务（移动远控路径）

| 项 | 内容 | 坐标 |
|---|---|---|
| 工厂 | `function $8(e)`（`createWindowHostControllerRuntime`） | `hi:1,624,487` |
| 附件服务 | `function U()` / `i(U,"createAttachmentService")` | `hi:1,633,504` / `hi:1,633,554` |
| 投影存储 | `function F8(e)`（`createWindowHostControllerProjection`） | `hi:1,613,625` |
| 覆盖注册 | `let h=Xr.createAttachmentService(), v=new Map([[GP.channelName,h]])` | `hi:1,664,910`（`uDe`） |

**方法表（8 个）**：

| 方法 | 位置参数 | 返回 |
|---|---|---|
| `subscribeControllerV4` | `[{topic}]` | `{ack:{subscriptionId, mode:"snapshot", logEpoch}}` |
| `resyncControllerV4` | `[{subscriptionId}]` | `{ack:{subscriptionId, mode:"snapshot", logEpoch}}` |
| `unsubscribeControllerV4` | `[{subscriptionId}]` | `undefined` |
| `onDynamicControllerFrame` | `listen`，**无 scope 参数** | 事件流（帧） |
| `mutateTask` | `[{address, mutation}]` | 任务 `meta` 或 `null` |
| `deleteArchivedTask` | `[{address}]` | `boolean` |
| `deleteArchivedTasks` | `[{address, taskIds}]` | `{deletedTaskIds, skippedTaskIds, failedTaskIds}` |
| `dispose` | `[]` | `undefined` |

> `subscribeControllerV4` 只读 `N.topic`（`hi:1,633,131`），**不读 base/visibility**；
> 这是“附件服务”版本，不是客户端 schema 的完整参数集。

### 4.2 `address` 的确切字段

Schema `ar`（`t2:53,519`，strict）：

```js
ar = e.object({
  remoteSessionId: e.string().trim().min(1).optional(),
  workspacePath:   e.string().trim().min(1),
  workspaceIdentity: e.string().trim().min(1).optional(),
  taskId:          e.string().trim().min(1)
}).strict().superRefine((t,o)=>{
  t.remoteSessionId && !t.workspaceIdentity &&
    o.addIssue({code:"custom", message:"remote task address requires workspaceIdentity", path:["workspaceIdentity"]})
})
```

即：**远程任务必须同时给 `remoteSessionId` 与 `workspaceIdentity`**；本地任务给 `workspacePath + taskId` 即可。

服务端匹配 source 的键（`hi:1,617,617` 附近）：

```js
t.scope.kind==="remote" ? t.scope.remoteSessionId===address.remoteSessionId
                        : address.remoteSessionId==null
// 同时要求 workspacePath / workspaceIdentity 相等
```

### 4.3 `mutation` 判别联合（逐字段，原文）

`hi:1,626,185`（`executeMutation` 的 `switch(N.kind)`）：

| `kind` | 其余字段 | 行为 |
|---|---|---|
| `"pin"` | `pinned: boolean` | `taskService.setTaskPinned({...address, pinned})` |
| `"archive"` | `archived: boolean` | `true→archiveTask`；`false→unarchiveTask` |
| `"delete"` | 无 | `taskService.deleteTask(address)` |
| `"delete-archived"` | 无 | `taskService.deleteArchivedTask(address)`，返回 `boolean` |
| `"delete-archived-batch"` | `taskIds: string[]` | `taskService.deleteArchivedTasks({workspacePath,workspaceIdentity,taskIds})`；若 source 已被替换抛 `归档删除批次的 source 已替换` |
| `"mark-read"` | `expectedUnreadAt?: number` | `setTaskUnread({...address, unread:false, expectedUnreadAt?})` |
| `"mark-unread"` | 无 | `setTaskUnread({...address, unread:true})` |
| `"open"` | 无 | **空操作**（`break`），随后刷新 source 并返回 meta |
| `"resume"` | 无 | **空操作**（`break`），同上 |
| 其他 | — | `Error("未知 Controller mutation: <value>")` |

`mutate` 前置校验（`hi:1,617,617`）：

```js
if(!source || mutation.kind!=="delete-archived" && mutation.kind!=="delete-archived-batch"
   && !source.rows.has(taskKey(address)))
  throw new Error("没有与任务地址匹配的 source")
if(source.sourceAvailability!=="online")
  throw new Error(source.scope.kind==="remote"
    ? "远程 source 当前离线，禁止列表写操作" : "本地 source 当前不可用")
```

`mutateTask` 返回值（`hi:1,632,716`）：

```js
await t.mutate(address, mutation)
// 刷新对应 source 后：
return t.getTasks().find(row => 地址四元组完全相等)?.meta ?? null
```

### 4.4 `subscribeControllerV4` 与帧形状

**客户端参数 schema**（`t2:57,110` `am`，strict）：

```js
{ topic: "controller/workspaces" | "controller/tasks-index",
  base?: {logEpoch:string, seq:int>=0},
  visibility?: "foreground" | "background" }
```

**resync 参数**（`t2:57,337` `dm`）：`{subscriptionId, base:{logEpoch,seq}|null, forceSnapshot?}`
**unsubscribe 参数**（`t2:57,574` `um`）：`{subscriptionId}`
**ack**（`t2:11,670` `X`）：`{subscriptionId:string, mode:"snapshot"|"resume", logEpoch:string}`

**帧 schema**（`t2:56,235` `lr`，两个 topic 各一份：`im`=workspaces、`sm`=tasks-index）：

```js
{
  topic: <literal>,
  subscriptionId: string,
  logEpoch: string,
  fromSeq: int>=0,
  toSeq: int>=0,
  sentAt: number,
  payload:
    {kind:"snapshot", snapshot: <snapshot>}
  | {kind:"deltas", deltas: [<delta>]}
}
```

约束（`superRefine`）：
- `toSeq >= fromSeq`；
- `payload.kind==="snapshot"` 时 **`fromSeq` 必须为 0**，且 `snapshot.logEpoch === frame.logEpoch`。

**任务快照**（`t2:55,677` `os`）：`{protocolVersion:1, logEpoch, tasks:[taskRow]}`
**工作区快照**（`t2:55,567` `ts`）：`{protocolVersion:1, logEpoch, workspaces:[workspaceFact]}`

**任务行 `taskRow`**（`t2:54,772` `dr`，strict）：

```js
{
  address: ar,
  meta: bn,                                  // 任务元数据，见下
  membership: {pinned:boolean, archived:boolean, active:boolean},
  sourceAvailability: "online" | "offline",
  liveStatus: "idle"|"running"|"waiting"|"completed"|"error",
  activity?: {
    phase: "draft"|"prewarming"|"running"|"completedSuccess"|"completedInterrupted"|"error",
    lastActivityAt: number,
    hasBackgroundWork: boolean,
    pendingInteractions?: {permissionCount:int, userInputCount:int},
    workflowActivity?: {runs: [...]}
  },
  searchSnippets?: string[]
}
```

约束：`address.taskId/workspacePath/workspaceIdentity` 必须与 `meta` 中对应字段一致。

**任务元数据 `bn` = `xj`**（`ou:712,770`）：

```js
{
  taskId, traceId, title,
  titleOverridden?: boolean,
  workspacePath, workspaceIdentity?,
  workspacePurpose?: "project"|"conversation",
  createdAt, updatedAt,                        // int>=0
  mode: enum("default","yolo","guarded","plan","edit","acceptEdits","auto",
             "dontAsk","bypassPermissions","autoEdit","build"),
  model?: string,
  thoughtLevel?: string,
  runtimeEpoch?: int>=0,
  provider?: "glm",
  migrationSource?,
  forkedFromTaskId?, cronAutomationId?, offPeakTaskId?,
  unreadAt?: int>=0,
  status?: "running"|"completed"|"error",
  lastError?: {code?, detail?, message, traceId?, taskId?, attribution?},
  repairState?: {...},
  changeSummary?: {fileCount, added, removed, files:[{path,added,removed,writeCount,lastTurnIndex}]},
  target?: goalTarget|null
}
```

**工作区事实 `workspaceFact`**（`t2:53,895` `cr`，strict）：

```js
{ remoteSessionId?, workspacePath, workspaceIdentity?,
  sourceAvailability:"online"|"offline",
  connectionState:"connecting"|"online"|"closing"|"failed"|"disconnected"|"reconnecting" }
```

约束：`remoteSessionId` 必须有 `workspaceIdentity`；`offline` 时 `connectionState` 只能是 `disconnected` 或 `failed`。

**delta 联合**：
- 任务（`t2:56,083` `rs`）：`{op:"task.upserted", task:taskRow}` | `{op:"task.removed", address:ar}`
- 工作区（`t2:55,782` `ns`）：`{op:"workspace.upserted", workspace:workspaceFact}` |
  `{op:"workspace.removed", remoteSessionId?, workspacePath, workspaceIdentity?}`

**topic 常量**（`t2:53,441`）：`controller/workspaces`（`ir`）、`controller/tasks-index`（`sr`）。

**帧产生逻辑**（`hi:1,613,625` `F8`）：

```js
// 快照（订阅时立即发一次）
{subscriptionId, logEpoch, fromSeq:0, toSeq:<当前seq>, sentAt:Date.now(),
 payload:{kind:"snapshot", snapshot:{protocolVersion:1, logEpoch, tasks:[...]}}}
// 增量
{..., fromSeq:<旧seq>, toSeq:<seq+1>, payload:{kind:"deltas", deltas:[...]}}
```

- 每个 topic 独立维护 `{logEpoch, seq}`，`logEpoch` 在投影创建时生成。
- 帧只发给 `topic` 相同的订阅（`h.topic===Na` / `ld`）。
- 客户端 schema 有 **1024 帧 / 32 MiB** 的首帧暂存上限（`t2:58,781` `ss=1024`；`t2:58,793` `as=32*1024*1024`），
  这是客户端侧保护，不是服务端限制。

**错误**：
- `subscribe` 的 topic 不在两个常量中 → `r[topic]` 为 `undefined` → 读 `logEpoch` 抛 TypeError → 202。
- `resync` 未知 subscriptionId → `Error("未找到 Controller subscription，subscriptionId=<id>")`。
- `unsubscribe` 未知 subscriptionId → 静默 no-op（`q.get(id)?.dispose()`）。
- 远程 source 缺 `workspaceIdentity` → `Error("远程 Controller source 必须携带 workspaceIdentity")`（注册阶段）。

---

## 5. `zcode-task` 通道中的任务操作（远程桥可用性）

### 5.1 移动远控路径的代理

`hi:1,663,149` `function lDe(e,t)`（`createControllerRoutedTaskService`）：

```js
let n = async (r,o) => Xr.service.mutateTask({
  address: await Xr.resolveTaskAddress({
    taskId: r.taskId, workspacePath: r.workspacePath,
    workspaceIdentity: r.workspaceIdentity,
    attachmentScope: t
  }),
  mutation: o
});
return new Proxy(e, { get(r,o,s){
  if(o==="setTaskPinned") return async c => {
    let d = await n(c,{kind:"pin",pinned:c.pinned});
    if(!d) throw new Error("pin mutation 后 task 投影缺失");
    return d;
  };
  if(o==="archiveTask"||o==="unarchiveTask") return async c => {
    let d = await n(c,{kind:"archive",archived:o==="archiveTask"});
    if(!d) throw new Error("archive mutation 后 task 投影缺失");
    return d;
  };
  if(o==="deleteTask") return async c => { await n(c,{kind:"delete"}) };
  if(o==="deleteArchivedTasks") return async c => c.taskIds.length===0
    ? {deletedTaskIds:[],skippedTaskIds:[],failedTaskIds:[]}
    : Xr.service.deleteArchivedTasks({address: await Xr.resolveTaskAddress({
        workspacePath:c.workspacePath, workspaceIdentity:c.workspaceIdentity,
        taskId:c.taskIds[0], attachmentScope:t, allowMissingTask:true}), taskIds:c.taskIds});
  if(o==="deleteArchivedTask") return async c => Xr.service.deleteArchivedTask({
    address: await Xr.resolveTaskAddress({...c, attachmentScope:t, allowMissingTask:true})});
  if(o==="setTaskUnread") return async c => {
    let d = await n(c, c.unread
      ? {kind:"mark-unread"}
      : {kind:"mark-read", ...c.expectedUnreadAt!=null?{expectedUnreadAt:c.expectedUnreadAt}:{}});
    if(!d) throw new Error("unread mutation 后 task 投影缺失");
    return d;
  };
  let a = Reflect.get(r,o,s);
  return typeof a==="function" && ["getBotGroupTaskBlockReason","cancelBotGroupInput",
    "submitBotGroupInput","readBotTopicSummaries"].includes(String(o))
    ? c => a.call(r, M8(c,t))
    : typeof a==="function" ? a.bind(r) : a;
}})
```

### 5.2 五个任务方法是否可用

| 方法 | 远程桥可用 | 实际执行路径 | 入参（RPC 位置参数） |
|---|---|---|---|
| `setTaskPinned` | **是** | `window-controller.mutateTask({kind:"pin"})` | `[{taskId,workspacePath,workspaceIdentity?,pinned:boolean}]` |
| `archiveTask` | **是** | `mutateTask({kind:"archive",archived:true})` | `[{taskId,workspacePath,workspaceIdentity?}]` |
| `unarchiveTask` | **是** | `mutateTask({kind:"archive",archived:false})` | 同上 |
| `deleteTask` | **是** | `mutateTask({kind:"delete"})`，返回 `undefined` | 同上 |
| `renameTask` | **是**（未被代理覆盖） | 回落到基础 `createZCodeTaskServiceAdapter`（`hi:409,792`） | `[{taskId,workspacePath,workspaceIdentity?,title:string}]` |
| `setTaskUnread` | **是** | `mutateTask({kind:"mark-read"/"mark-unread"})` | `[{taskId,workspacePath,workspaceIdentity?,unread:boolean,expectedUnreadAt?:number}]` |

> 注意：代理的 `resolveTaskAddress` 额外接受 `attachmentScope`（`{kind:"remote",remoteSessionId,workspacePath,workspaceIdentity}`）
> 并做一致性校验；RPC 调用方不需要传它——它由 host 侧闭包 `t`（`o` = `{kind:"local"}` 或 remote scope）注入。

**基础实现语义**（`hi:457,847` 起）：

| 方法 | 行为 |
|---|---|
| `deleteTask` | `Xe({deleted:true})` 覆盖层 + 仓库 `{deleted:true}` + 事件 `task_deleted`；**不删磁盘会话** |
| `renameTask` | 覆盖层 `{title}` + 仓库 `{title,updatedAt:now,titleOverridden:true}` + `sendConversationCommandV4({type:"renameSession",payload:{title}})`；同步失败只告警，不回滚；事件 `task_title_changed` |
| `setTaskPinned` | 覆盖层 + 仓库 `{pinned}`；事件 `task_pinned` / `task_unpinned` |
| `setTaskUnread` | `unread:true` → `unreadAt=Date.now()`；`unread:false` + `expectedUnreadAt` → `clearTaskUnreadIfMatches`（CAS）；事件 `task_meta_changed` |
| `archiveTask` / `unarchiveTask` | 覆盖层 + 仓库 `{archived}`；事件 `task_archived` / `task_unarchived` |
| `deleteArchivedTask` | 仓库删除，返回 `boolean`；成功则 `{deleted:true}` + `task_deleted` |
| `deleteArchivedTasks` | 去重后逐个删；返回 `{deletedTaskIds,skippedTaskIds,failedTaskIds}`；有成功项才发 `task_deleted` |

> 这些方法在基础服务里**没有 zod 校验**，缺字段会在后续 `ge()`/仓库调用处抛 TypeError 或产生空操作。

---

## 6. 哪些设置是桌面本地、哪些远程可改

### 6.1 服务端是否有键级限制

**没有。** 三条证据：

1. `setting` 通道在移动远桥 `exposeServicesOnMessagePort`（`hi:1,664,910`）中**不在覆盖 Map 里**，
   直接使用本地服务集合中的 `s`；覆盖只针对 `window-controller`、`zcode-task`、`zcode-agent`、
   `conversation-share`（条件 `media-preview`）。
2. `update` 内部只做 schema 校验（`QS.parse`），没有“桌面键/远程键”白名单。
3. `get` 返回全部 54 键，不做裁剪。

因此：**54 个键远程都能读、都能写**。区别只在于“写完之后谁消费、何时生效”。

### 6.2 分类表（键 → 消费方 → 远程写入的实际效果）

| 类别 | 键 | 消费方（证据） | 远程写入效果 |
|---|---|---|---|
| **A. 主机/代理进程消费（远程改有效）** | `terminalInheritSystemProfile`、`terminalFontFamily` | host 终端服务 `eW`（`hi:240,903`） | 下一次 `terminal.create` 生效 |
| | `integratedTerminalShell` | host 终端 shell 选择 + agent runtime preferences（`hi:1,538,077`） | 下一次会话/终端生效 |
| | `httpProxy`、`httpProxyNoProxy`、`httpProxyCaCertPath` | host 网络层 `xc(...)`（`hi:1,575,262` 读 `httpProxy/noProxy/caCertPath`） | 下一次 agent 启动生效 |
| | `taskAutoArchiveEnabled`、`taskAutoArchiveOlderThanDays` | host 自动归档（`hi:421,865` 的 `readTaskAutoArchiveConfig`） | 下一轮自动归档生效 |
| | `askUserQuestionAutoResolutionEnabled`、`nativeSearchEnhancementsEnabled`、`memoryEnabled` | host agent runtime preferences（`hi:1,538,077`、`hi:1,578,317`） | 下一次运行时偏好同步生效 |
| | `modelIoFullRetentionEnabled` | host `syncAppRuntimePreferences`（`hi:334,954`） | 下一次同步生效 |
| | `dynamicWorkflowMode` | host `syncDynamicWorkflowUserMode` | 下一次同步生效 |
| | `enabledBuiltinAgentCliProviders` | host agent provider 列表；解析后恒为 `["glm"]` | 实际不可变 |
| | `providerFamilyDomain`、`providerFamilyConnectionSelections`、`providerFamilyDomainUpdatedAt`、`providerFamilyDomainMigrated` | host 账号 Provider 解析 / provisioning | 立即（下次刷新） |
| | `dataBaseDir` | host/main 数据目录（`updateDataBaseDir` 会复制目录；网页端提示 `settings.dataBaseDirRestartRequired`，`web:6,219,979`） | 需重启；远程调用会真的搬目录（危险） |
| | `zcodeEndpointOrigin` | main/host 启动时解析 endpoint | 需重启 |
| **B. 桌面窗口 / 渲染进程消费（远程写只影响桌面）** | `desktopWindowSize`、`desktopZoomLevel`、`desktopChromiumHardwareAccelerationEnabled` | main 窗口/GPU（`out/main/index.js` 中 `desktopWindowSize`） | 桌面端下次读取/重启；移动端无感 |
| | `closeToTrayOnWindows`、`closeToTrayOnWindowsMigrationInitialized`、`closeToTrayLinuxMigrationInitialized` | main 托盘（`out/main/index.js` 中 `closeToTrayOnWindows`） | 同上 |
| | `keepAwakeWhileRunning` | main 电源保持（`out/main/index.js` 中 `keepAwakeWhileRunning`） | 同上 |
| | `shortcutBindings` | main 菜单快捷键（`out/main/index.js` 中 `shortcutBindings`）+ renderer | 同上 |
| | `embeddedBrowserAllowInsecureCertificates`、`embeddedBrowserViewportPreference` | 桌面内置浏览器 | 同上 |
| | `computerUseComposerEntryHidden` | 桌面 CUA 输入框入口（`web:2,159,051`、`web:6,187,822`） | 同上 |
| | `locale`、`localePreference` | renderer i18n | 同上 |
| | `messageStreamShowReasoning`、`messageStreamShowTodos`、`toolGroupingExploreEnabled`、`toolGroupingTerminalEnabled`、`toolGroupingChangesEnabled`、`zcodeInteractionBehavior` | renderer 会话渲染（`web` 中 `messageStreamShowTodos` @3,190,461；`c$n=["queue","guide"]` @6,225,137） | 同上 |
| | `recentProjects`、`lastWorkspaceSession`、`lastActiveTabIndex`、`lastActiveTaskByWorkspace` | renderer 标签页恢复（`web:5,760,295` `useTabPersistence`） | 同上 |
| | `startPlanRecommendationDismissed`、`settingsSyncFirstRunPromptHandled` | renderer 引导 | 同上 |
| | `onboardingOccupation`、`proactiveSuggestionsEnabled` | renderer 引导 + onboarding-record | 同上 |
| **C. 桌面更新 / 远程控制（桌面本地才有意义）** | `receivePreviewUpdates`、`autoDownloadAndInstallUpdates`、`skippedElectronUpdateVersions`、`pendingPostUpdateReleaseNotes` | Electron updater（main） | 只影响桌面更新 |
| | `webRemoteControlExternalRelayDevice`、`webRemoteControlLastEnabledContext` | 桌面远控 relay 配置 | 只影响桌面远控入口 |
| **D. 迁移标记（无独立语义）** | `closeToTrayOnWindowsMigrationInitialized`、`closeToTrayLinuxMigrationInitialized`、`messageStreamShowReasoningMigrationInitialized`、`providerFamilyDomainMigrated` | 迁移逻辑 | 手改可能触发重复迁移 |

> 关于“远程写后桌面是否热感知”：**UNKNOWN**。`setting` 通道没有广播；桌面渲染进程的 `uk()` 只在
> 自身 `update` 或 host `onSettingsChanged` 时重读（`web:1,436,199`）。远程写入是否触发该信号，静态未定位到。

### 6.3 不在 `setting.json` 里的“设置”

网页/桌面 UI 有一批键**只存在于渲染进程**，不在 `setting.get()` 的 54 键内，因此**远程完全不可见、不可改**：

| 键 | localStorage key | 证据 |
|---|---|---|
| `notificationEnabled` | `zcode-notification-enabled` | `web:1,189,494` |
| `notificationSoundEnabled` | `zcode-notification-sound-enabled` | `web:1,189,527` |
| `interfaceMode` | `zcode-interface-mode`（`as`/`rs` 读写） | `web:1,189,884` |
| `codePreviewSettings` | `zcode-code-preview-settings`（`as` 读） | `web:1,189,998` |
| `performanceMode` | `zcode-performance-mode`（`as` 读） | `web:1,190,036` |

> 通知两键的读写实现明确是 `localStorage`：`R2e(e,t)=localStorage.getItem(e)`、`z2e(e,t)=localStorage.setItem(e,String(t))`（`web:1,188,676` / `web:1,188,773`）。
> `interfaceMode/codePreviewSettings/performanceMode` 走 `as`/`rs` 两个绑定，其底层实现未在静态扫描中定位（**UNKNOWN** 是否 localStorage）；
> 但可确定它们**不经过 `setting` 通道**。

**文件搜索设置也不在 `setting.json`**：工作区文件搜索忽略规则由 `file` 通道读写工作区内的
`.zcodeignore`（优先）与 `.gitignore`（模板）：
`var qs=".zcodeignore",Nh=".gitignore"`（`hi:137,453`），
`readWorkspaceFileSearchIgnore(rootPath)` → `{content,source:"file"|"template"}`（`hi:141,868`），
`writeWorkspaceFileSearchIgnore({rootPath,content})`（`hi:150,064`）。
网页设置页的 `workspaceFileSearch` 分组（`web:6,223,339` 的 `i$n`）操作的就是它，与 `setting` 通道无关。

### 6.4 网页设置页分组（`i$n`，`web:6,223,339`）

```js
basics:           general, appearance, modelProvider, browser, computerUse, shortcuts, workspaceFileSearch
agentCapabilities: memory, subagents, plugin, mcp, skill, commands, automations, hooks
dataAndStats:      usage
```

分组标题数组 `r$n`（同处）：`basics / agentCapabilities / dataAndStats`。

---

## 7. UNKNOWN / 未证实清单

1. **`onDidUpdate` 退订路径**：服务返回函数，`ee` 包装器按 `{dispose}` 处理（`wm:17,672`），
   最后一个监听者移除时 `s?.dispose()` 可能抛 `TypeError`。是否被 emitter 吞掉未实测。
2. **远程写入后桌面端是否热更新**：未找到 `setting` 变更的跨进程广播；桌面渲染进程重读机制见 §6.2 末尾。
3. **`provider-settings` 视图 `providerTemplates[].templateNameMap` 的内部形状**：只知其存在，字段未展开。
4. **`addPersonalModel` 第 4 个参数 `options` 的形状**：门面原样透传给仓库 `setExact`，未展开。
5. **`provider-provisioning-target` 在移动远控路径是否注册**：取决于 host 的
   `serviceAuthorityMode==="desktop-attached-remote"` 或 `providerProvisioningTargetEnabled===true`；
   移动端实际可见性未实测（与 08 文档 §8.3 一致）。
6. **`Provider 配置 JSON` 的 `api.type` 枚举值**（`XX`）：未展开。
7. **`settings-sync` 等其他通道是否也会写这 54 键**：未在本文范围内。
8. **`onboardingOccupation` 的 14 值**：`office, developer, independent, infrastructure, product, design,
   student, creator, operations, marketing, finance, accounting, legal, other`（`ou:605,886`）——已证，列在此仅为核对。

---

## 8. 坐标索引（字节偏移）

### 8.1 `out/host/index.js`

| 偏移 | 内容 |
|---:|---|
| 137,453 | `var qs=".zcodeignore",Nh=".gitignore"` |
| 141,868 | `readWorkspaceFileSearchIgnore` |
| 150,064 | `writeWorkspaceFileSearchIgnore`（file 通道） |
| 240,903 | 终端 `eW()`（消费 `terminalFontFamily/terminalInheritSystemProfile`） |
| 246,540 | `function iW(`（patch 归一化） |
| 250,435 | `var ure=10`（recentProjects 上限） |
| 250,815 / 250,883 | `getSettingsDir` / `getSettingsFile` |
| 250,973 | `lu.parse({})`（defaultSettings） |
| 253,434 | `function II(`（createSettingService 入口） |
| 253,497 | `function Ed(`（createSettingServiceWithMigrations） |
| 254,027 | `async update(d,l)`（QS.parse / lu.parse / expected） |
| 409,792 | `function gb(`（createZCodeTaskServiceAdapter） |
| 457,847 | `async deleteTask(y)` |
| 458,871 | `async renameTask(y)` |
| 460,314 / 460,454 / 460,930 | `setTaskPinned` / `setTaskUnread` / `archiveTask` |
| 475,732 / 475,835 | `Tr="glm"` / `is=[Tr]` |
| 521,126 / 521,192 | `cC`（Provider 配置解析）/ `Sh`（模型配置解析） |
| 701,148 | `vF`（Provider 配置序列化） |
| 708,888 | `kF`（模型配置序列化） |
| 79,392 | `var _h=class`（ProviderSettingsFacade） |
| 83,958 | `Rh=class`（ModelSelectionFacade） |
| 85,362 | `function AQ(`（createProviderSettingsView） |
| 1,044,496 | `function lT(`（provider-provisioning-target） |
| 1,456,694 | `function AY(`（createObservableSettingService） |
| 1,520,109 | `let o=e?.settingService?null:Ed()` |
| 1,541,460 | `.register(Ws,s)`（setting 通道注册） |
| 1,545,903 / 1,545,936 | `.register(pd,nt.providerSettings)` / `.register(gi,nt.modelSelection)` |
| 1,545,974 | `providerProvisioningTargetEnabled===!0`（条件注册） |
| 1,613,625 | `function F8(`（createWindowHostControllerProjection） |
| 1,617,617 | `async mutate(u,p)`（source 匹配 + 前置校验） |
| 1,624,487 | `function $8(`（createWindowHostControllerRuntime） |
| 1,626,185 | `case"pin":await Z.setTaskPinned`（mutation switch） |
| 1,627,832 / 1,631,176 | `yS` / `MOe`（任务 meta 归一化 / mutation 参数） |
| 1,632,716 | `async mutateTask({address:N,mutation:$})` |
| 1,633,131 | `async subscribeControllerV4(N)` |
| 1,633,504 / 1,633,554 | `createAttachmentService` |
| 1,663,149 | `function lDe(`（createControllerRoutedTaskService） |
| 1,664,910 | `function uDe(`（exposeServicesOnMessagePort，覆盖表） |

### 8.2 `out/host/chunk-OUCOFHX4.js`

| 偏移 | 内容 |
|---:|---|
| 475,732 / 475,835 | `Tr="glm"` / `is=[Tr]` |
| 557,076 / 557,157 | `bs`（viewport preference）/ `R_`（默认值） |
| 574,447 | `M_`（coding plan 选择） |
| 574,693 | `Dr`（`providerFamilyConnectionSelections`） |
| 575,083 / 575,379 / 575,500 / 575,557 | `XO` / `QO` / `ez` / `hY`（provisioning） |
| 598,085 | `go=["disabled","onDemand","alwaysOn"]` |
| 605,886 | `yx`（onboardingOccupation 枚举） |
| 606,064 / 606,091 / 606,120 / 606,158 | `F` / `Wr` / `bx` / `Sx` |
| 606,187 / 606,258 / 606,293 / 606,395 | `TM` / `_x` / `eh` / `Es` |
| 606,555 / 606,673 / 606,825 | `xx` / `th` / `PM` |
| 606,862 / 607,587 | `Ix`（remote target）/ `Cx`（lastWorkspaceSession） |
| 608,008 / 608,035 / 608,124 | `Rx` / `wx` / `nh` |
| 612,218 | `var jM=e.object(`（完整设置 schema，54 键） |
| 614,859 / 614,916 | `LM=e.preprocess(` / `ZM=e.object(`（patch schema） |
| 712,770 | `var xj=e.object(`（任务 meta schema `bn`） |
| 821,255 / 821,264 | 导出 `LM as Ac`（= `lu`）/ `ZM as Bc`（= `QS`） |

### 8.3 `out/host/chunk-T2GB4I3K.js`

| 偏移 | 内容 |
|---:|---|
| 11,670 | `X`（ack schema） |
| 53,441 | `var ir="controller/workspaces",sr="controller/tasks-index"` |
| 53,519 | `ar`（任务 address） |
| 53,895 | `cr`（工作区事实） |
| 54,595 | `es`（activity） |
| 54,772 | `dr`（taskRow） |
| 55,567 / 55,677 | `ts` / `os`（工作区/任务快照） |
| 55,782 / 56,083 | `ns` / `rs`（工作区/任务 delta） |
| 56,235 | `function lr(`（createControllerFrameSchema） |
| 57,078 / 57,094 | `im` / `sm`（两个 topic 的帧 schema） |
| 57,110 / 57,337 / 57,574 | `am` / `dm` / `um`（subscribe / resync / unsubscribe） |
| 72,376 / 72,428 | `kt=f(h.ProviderSettings)` / `function Vg(` |
| 74,600 / 74,638 | `a(Vg,"createProviderSettingsService")` / `function Gg(` |
| 75,303 | `a(Gg,"createModelSelectionService")` |
| 80,468 | `var xt=f(h.Setting)`（→ 导出 `Ra`，host 别名 `Ws`） |

### 8.4 `Reference_web/3.15.3/assets/index-BGa3KiRe.js`

| 偏移 | 内容 |
|---:|---|
| 1,189,494 / 1,189,527 | `zcode-notification-enabled` / `zcode-notification-sound-enabled` |
| 1,189,676 / 1,189,773 | `R2e`（localStorage 读）/ `z2e`（写） |
| 1,436,199 | `function uk(`（设置 store：`get` + 手动重读） |
| 1,572,011 | `function Mct(`（provider-settings store：`getView` + `onDidChange`） |
| 5,762,997 / 5,763,095 | `deletePersonalProvider` / `savePersonalProviderOverlay` 调用 |
| 5,763,193 | `savePersonalProviderOverlay(providerId, overlay, {providerName?,enabled?})` |
| 5,764,376 | `savePersonalModelDraft(e)` 调用 |
| 6,223,339 | `i$n=[{id:"general"...}]`（设置页分组） |
| 6,566,648 | 无桥桩实现（get/update/updateDataBaseDir/ensureDefaultProject） |
