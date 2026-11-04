# 注入层 pre-device 审查（commit `0b03c56..HEAD`）

审查范围：`android/app/src/main/assets/inject.js`、`android/tools/inject.test.js`（`zcode-protocol.js` 在该区间**无改动**，已用 `git diff --stat` 确认）。
原生侧（`ShellRuntime.kt` / `MainActivity.kt`）只作为"注入层这套逻辑是否真的生效"的证据被引用，不在本次逐行审查范围。

**结论：P0 = 0，P1 = 3，P2 = 7。**
新增代码本身没有发现会写页面 socket、改动页面 DOM（除既有的刷新按钮）、或在未判闸门时主动 `location.reload()` 的路径；风险集中在"闸门③覆盖面不完整""pin 标记的清除时机""KICKED 归因缺身份匹配"三处，以及一处无条件 DOM 扫描。

`node --test tools/inject.test.js` 实跑：74 项全绿（含新增 6 条）。

---

## P1（特定时序会坏）

### P1-1 版本闸门探测在**每一次 DOM 变更拍**都做一次全文档属性选择器扫描

- **位置**：`inject.js:3397`（`pushPageState` 里无条件调用）、`inject.js:3370-3377`（`versionGateBlocked`）、`inject.js:3426-3435`（合并拍）、`inject.js:3516-3523`（观察器：`subtree+childList+attributes`）
- **触发**：页面正常使用即可，不需要异常。React 每批 DOM 变更 → `schedulePageState()` → `setTimeout(0)` → `pushPageState()` → 一次 `document.querySelector('dialog[aria-labelledby="remote-version-title"]')`。属性选择器在 Blink 里没有索引，是整棵树逐节点匹配；同一拍里 `noteChatViewEntered()`（`inject.js:3450`）本来就已经扫了一遍 `[data-mobile-page="chat"]`，新增这一条把每拍的全文档扫描从 1 次变成 2 次。
- **证据**：去重只发生在"发帧"上（`gate !== lastVersionGate` 才 `post`，`inject.js:3398-3404`），**探测本身没有任何缓存/短路**；也没有"模态框根本不存在时跳过"的廉价前置判据。
- **未实测**：单拍开销与 SPA 的实际变更频率（流式输出时约每秒几十拍）未在真机量化，量级判断是"每拍亚毫秒到毫秒级"，需要真机 Perfetto 或 `Performance.now()` 落点确认——但代码路径是确定的无条件执行。
- **建议修法**：给探测加最小间隔（如 ≥250ms，`<300ms` 的交还预算仍满足），并在 `__zcodeShellReportPageState`（`inject.js:3540`）里强制探测一次；或先判 `documentElementsCount` 级的廉价条件再查询。

### P1-2 pin 标记的清除不区分"用户回首页"与"页面失败态把对话视图卸掉"

- **位置**：`inject.js:3447-3464`（`noteChatViewEntered` 的 true→false 即清）、`inject.js:258-282`（写入侧）、`inject.js:1107`（KICKED 时 `pageFailure='kicked'`）
- **触发**：用户停在对话页 → 被 KICK 或链路失败 → 页面切到"已被其他设备接管"终态，`[data-mobile-page="chat"]` 消失 → 观察器判 true→false → `storeSet(PIN_CHAT_KEY, '')`。此后若发生一次 `location.replace()` 闸门导航或原生重载，而那一刻 `config().restoreChat` 恰好为 false（注释 `inject.js:249-253` 明确说这正是"viewAtLeave 已被前面那次判定消费掉"的形态），则**闸门①唯一的生命线已经在上一拍被自己清掉了**，用户回到首页而不是他离开的对话。
- **证据**：清除条件只有 `has === false`，没有检查 `pageFailure`、`versionGateBlocked()`、"是否真的发生了用户导航"（无 URL/视图双重确认）。
- **建议修法**：清除前加两个 guard：`if (pageFailure) return;` 与 `if (versionGateBlocked()) return;`。失败态/闸门态下的视图缺失不代表用户离开了对话页。

### P1-3 KICKED 归因没有身份匹配，且"同一件事"存在两套判据；承载窗口内发生外部抢占会被误判成自我接管

- **位置**：`inject.js:1308-1337`（窗口只有 `at` 时间上限，`workspace`/`session` 记了却从不比较）、`inject.js:1409-1420`（`noteRelayKicked` 分支内只记日志）、`inject.js:1313-1324`（窗口只能由原生关，页面侧配对成功不清窗）；行为侧真相在 `ShellRuntime.kt:1298`（`carrierMayHaveKickedPage() = isRunning || carrierRanThisStint`，**无 5 分钟上界**），`ShellRuntime.kt:2410-2420`（据此盖罩 + 交还后重载）
- **触发**：承载运行期间（原生窗口开、`carrierRanThisStint=true`），用户在另一台控制端接管会话——relay 单控制端互斥，页面与原生承载会**同时**被 KICK。此时注入层判 `kickedByOwnCarrier()===true`（窗口在、且未超 5 分钟），原生判 `carrierMayHaveKickedPage()===true`（`isRunning` 仍为真 / `carrierRanThisStint` 未复位），于是外部抢占被当成自我接管：盖罩 → 交还后重载 → 新文档重连后**反把刚接管的那台设备顶掉**（互斥，最后连上者胜），用户可以感知到互相抢线。
- **证据**：`carrierWindow.workspace`/`session` 只在 `inject.js:1414` 拼进日志，没有与 `lastPageWorkspace` 或当前 socket 的会话做任何比较；`kickedByOwnCarrier()`（`inject.js:1327-1337`）只有时间判据。旧实现里"配对成功即清零"的复位（本次删除的 `relayKicked` 分支）现在没有任何替代物——窗口只能等原生调 `closeCarrierWindow()`。两侧判据不一致：注入侧有 5 分钟上界，原生侧没有，因此测试 `inject.test.js:1992`（超 5 分钟归外部）钉住的是**日志文案**，不是真机行为。
- **建议修法**：原生在 `onKicked`（`ShellRuntime.kt:1973`，承载自己也被踢 ⇒ 一定是外部）里立即 `closeCarrierWindow()` 并复位 `carrierRanThisStint`；注入层在 `kickedByOwnCarrier()` 里加 `carrierWindow.workspace === lastPageWorkspace` 的比较；两侧共用同一条判据，别各写一套。

---

## P2（质量 / 健壮性）

### P2-1 闸门③的判据只看节点存在，不看 `open` / 可见性

- **位置**：`inject.js:3370-3377`
- **触发**：只要页面把那个 `<dialog>` **常驻挂载**（关闭状态也只是 `open` 属性不在），判据就永久为真 → 原生 `requestPageReload`（`ShellRuntime.kt:1917`）与 `healDeadLinkOnResume`（`inject.js:1391`）**永久跳过所有重载**，页面卡死也不再自愈。
- **证据**：`3.17.1-anchor-audit.md:224` 只证实了**阻断时**是 `<dialog>` + `showModal()`，没有证实关闭时会被卸载；`showModal()` 会写 `open` 属性，因此判据可以更严而不会漏报。
- **建议修法**：选择器改成 `dialog[aria-labelledby="remote-version-title"][open]`，必要时再补 `getClientRects().length > 0`。

### P2-2 闸门③没有覆盖 `__zcodeShellRecoverConversation`，另有一个"文档里说被调用、实际没人调"的 reload 入口

- **位置**：`inject.js:1840-1847`（档 3：`pinChatRoute()` 后无条件 `location.reload()`，无闸门判断）、`inject.js:2750-2783`（交还兜底 reload）
- **触发**：闸门阻断态下，若用户离开时快照是 chat=true 而复判 chat=false（modal 顶掉了对话视图），原生 S3 判定成立 → 调到 `__zcodeShellRecoverConversation` → 重载 → 同一个对话框再来一次。原生侧 `allowConversationRecovery()`（`ShellRuntime.kt:526`）限到 2 次 + 5 分钟，所以是**有界**的重复重载，不是死循环。
- **证据**：`__zcodeShellAfterCarrierReturn` 在 Kotlin 侧**零调用点**（`grep AfterCarrierReturn android/app/src/main/java` 只命中注释），与 `android/README.md:494` "由 …兜底重载一次"及 `docs/android/09…` 的描述不一致——它目前是死代码，一旦接线就会绕过闸门③。
- **建议修法**：`__zcodeShellRecoverConversation` 入口处加 `if (versionGateBlocked()) return false;`；顺手确认 `__zcodeShellAfterCarrierReturn` 是接线还是删除。

### P2-3 pin 标记可跨登出/换工作区残留

- **位置**：`inject.js:258-282`；路径闸在 `MainActivity.kt:639-640`（只在 `/remote` 起始路径注入）
- **触发**：sessionStorage 是**标签页级**、跨同标签导航存活。`/remote` → 登录/回调页（不注入，标记不会被清）→ 重新登录回到 `/remote`，若此时 `restoreChat` 不为 true，旧标记会把用户直接拽进对话页；换工作区同理（标记不区分工作区）。多标签不构成串扰（sessionStorage 不共享），这一条不是多标签问题。
- **证据**：只有 `noteChatViewEntered` 一个清除点（`inject.js:3458-3464`）；观察器缺失（`inject.js:3527-3533` 的降级分支）或页面直接以 home 形态加载（没有 true→false 迁移）时永不清理。
- **建议修法**：写入时同时记 `documentId` 或原生给的加载序号，读到不匹配就丢弃；或在原生 `onLeftRemotePage` 里顺手 `evaluateJs("sessionStorage.removeItem('zcodeShellPinChat')")`。另外 `inject.js:267` 的 `storeSet` 在 `pinChatRoute()` 之前，若 `replaceState` 抛错，标记仍会被留下——把它放到 `pinChatRoute()` 之后。

### P2-4 `desktopversion` 每帧重复上报，无去重

- **位置**：`inject.js:1161-1173`
- **触发**：每次收到 `bootstrap-response` 就 `post` 一次（重连、刷新 bootstrap 都会重来）。原生 `DesktopVersion.learn` 自己有等值短路（`DesktopVersion.kt:30`），所以是噪音不是故障。
- **建议修法**：模块内记 `lastReportedDesktopVersion`，相同则不发（与 `lastPageState` 同一写法）。

### P2-5 原生把含 `sid` 的完整 `href` 写进日志（预存在，但与本轮"凭证不入日志"的纪律相左）

- **位置**：`inject.js:4183`（`post('ready',{href})`）→ `ShellRuntime.kt:2448-2451`（原样打印）——不在本 diff，属既有行为
- **证据**：注入层自己在 `inject.js:1066-1068` 把 `device_sid` 标为"会话凭证，学但不记"，原生却把 URL 里的 `sid` 打进 `Diagnostics`。本轮新增的 `authToken` 没有这个问题：token 只出现在 `relaycreds` 一个帧里（`inject.js:358-365`），原生只记模式不记值（`ShellRuntime.kt:2365-2368`），`lastRelayUrl` 的日志全部走 `safePath`（`inject.js:2483`）。
- **建议修法**：原生日志改打 `safePath`/去 query 的 href；`ready` 帧可以只发路径。

### P2-6 `__zcodeShellExpectCarrier` 暴露在页面世界，页面脚本可自行改归因

- **位置**：`inject.js:1313`
- **触发**：注入脚本与页面共用 window；页面（或页面引入的任何第三方脚本）调用 `__zcodeShellExpectCarrier(Date.now(),...)` 后再触发一次 KICK，就能把后续 KICK 归类成"自我接管"。注入侧只影响日志，原生侧不受此函数影响，所以风险低（自伤型）。
- **建议修法**：如需收紧，用一个原生下发的随机标记做参数校验；否则在注释里显式承认"这是可被页面影响的可观测性字段"。

### P2-7 `NARROW_QUERY` 收窄后与旧版页面不一致（可接受，但要有版本边界）

- **位置**：`inject.js:3289`；使用点 `inject.js:3328`（状态条颜色）
- **触发**：只有"桌面端仍是 3.16.x（单条件断点）+ 设备是 fine 指针（折叠屏接鼠标/桌面模式）"时，壳报 `main-surface` 而页面按窄屏渲染，条带取错色；同版本页面下壳与页面判据逐字一致，不会错。
- **证据**：原生侧无第二份断点常量（`grep -rn "767\|max-width" android/app/src/main/java` 无命中），不存在双份定义漂移；测试把 media key 绑成整串（`inject.test.js:26,1208`），旧串会直接转红，改动是被钉住的。
- **建议修法**：无需改代码；在 41/42 号审计文档里补一句"页面 < 3.17.1 时该判据偏宽"，免得以后被当成回归。

---

## 逐类检查结论（六个问题）

1. **只读契约**：新增路径均未写页面 socket、未改页面 DOM（刷新按钮为既有功能，仅换了锚点查找方式 `inject.js:4014-4058`）、未新增无闸门的 `location.reload()`（`healDeadLinkOnResume` 已在 `inject.js:1391` 加闸）。新增的页面侧写入只有 sessionStorage 的 `zcodeShellPinChat`（命名空间键，与已删的 `zcodeShellKickedHeals` 同一先例）与既有的 `history.replaceState`。**通过**；残留见 P1-2、P2-2、P2-3。
2. **性能**：见 P1-1（探测无缓存、每拍一次全文档扫描；`<dialog>` 是否被频繁重挂**未实测**）。
3. **正确性**：`location.replace()` 后新文档能读回 sessionStorage（同标签跨文档存活，`inject.js:273` 在 document-start 读，时机正确）；清标记时机见 P1-2；多标签不串（sessionStorage 不共享）；5 分钟窗口内的外部抢占见 P1-3。
4. **凭证**：token 只经 `relaycreds` 一帧移交，注入层日志与 `diag` 均不含 token；`new URL()` 全部 try/catch；**通过**（原生 `href` 日志为预存在问题，见 P2-5）。
5. **兼容/降级**：`pageState` 观察器缺失时只是不再上报 `versiongate`（闸门③退化为不生效 = 旧行为），`versionGateBlocked()` 自身不依赖观察器（`inject.js:3370`）；`URL`/`sessionStorage` 抛异常均有兜底；`querySelector` 不存在有显式短路（`inject.js:3372`）；`NARROW_QUERY` 三条件见 P2-7。**通过**。
6. **测试**：新增 6 条均断言实质行为（值透传、出现/去重/消失三段、按钮插入、state 被改/不被改、凭证字段 + diag 无 token），不是"没抛异常"式断言。KICKED 5 条两侧都覆盖：窗口内不 reload（`inject.test.js:1929-1950`）、窗口外/已关闭/超时均不动手（`1952/1972/1992`）、后台文档 `pagefailure.foreground=false`（`2007`）。**缺口**：没有 workspace 不匹配用例（功能本身未实现，见 P1-3）、没有 pin 清除路径的用例（对应 P1-2）、没有"探测抛错时安全降级"的用例。

## 未验证项（不在本报告里当结论用）

- 版本闸门 modal 关闭时页面是否卸载 `<dialog>`（决定 P2-1 是理论风险还是实际风险）。
- 每拍 `querySelector` 的真机耗时与页面变更频率（P1-1 的量级判断）。
- 承载期间外部接管时，重载后的页面是否真的会反顶对方设备（P1-3 的行为链，依赖 relay 的互斥语义）。
