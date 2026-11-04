> ⚠️ Historical audit baseline (2026-09-18, pre-cleanup). Status as of 2026-09-19: see "Status verification" section at the end. Many items listed as dead have since been deleted in batches 1–4 (commit b627610).

# protocol-js — dead-code findings

## Scope audited

- `android-shell/app/src/main/assets/zcode-protocol.js` (2919 lines, 128 KB) — the wire layer of the
  shell: `rpc-frame` envelope (base64 + crc32 + fragmentation, send/assemble), `ChannelClient`
  value-codec stream, `SessionsIndexState` decoder, workspace-bridge lifecycle (`_bridges` /
  `_bridgesById`), the proactive self-subscribe conversation stream + watchdog, conversation-text
  decoding for the notification-card body, page-RPC tracing, and the feature-flag constants.
- Read in full (offset/limit sweeps, 1–2919). Cross-checked against `assets/inject.js`,
  `tools/protocol.test.js`, `tools/inject.test.js`, `tools/fake-desktop.js`,
  `app/src/main/java/com/zcode/remote/**` (all 32 Kotlin files via grep),
  `app/src/test/java/**`, `README.md`, and `docs/18-全仓审计-错误路线残留与清理.md` §2.1/§3.9/§4/§5.

**Production entry points (established, not assumed):** `inject.js:62` is the only production
reader of the module (`var P = G.ZcodeProtocol;`) and it uses exactly **one** export —
`new P.RemoteClient({...})` at `inject.js:789`. Kotlin never touches this file's symbols: it only
calls `window.__zcodeShell*` globals defined in `inject.js` (`MainActivity.kt:412` concatenates the
two assets; `MainActivity.kt:600/732/800`, `ShellRuntime.kt:223/241/261/548/589/1106/1271`,
`SettingsActivity.kt:107`).

**Method-call census on the single production instance** (`inject.js` only):
`sharedState: pageCoverage` (809), `acceptObservedPayload(...)` (1088 outbound, 1099 inbound),
`acceptPayload(...)` (1097 inbound), `latestConversationText()` (670), `conversationFrameStats()`
(683, 1365), `reportPageRpcWindow()` (1449), `lastPageConversationTrafficAt()` (1853, 2293),
`lastPageBridgeTrafficAt()` (2126, 2205), `pageBridgeSessionIds()` (2132, 2431), `dispose()` (884).
**Never called from production:** `start`, `openBridge`, `listWorkspaces`, `awaitPageIdle`,
`inFlightPageRpcs`, `isStarted`, `subscribeSessionsIndex`.
Search: `\.(start|openBridge|listWorkspaces|awaitPageIdle|inFlightPageRpcs|isStarted|subscribeSessionsIndex)\(` over `app/src/main/assets` → 0 production hits (`inject.js:897` is a comment; the 15 `client.start()` hits are all in `tools/protocol.test.js`); same pattern over `app/src/main/java` → 0 hits.

---

## Findings

### F1. Constant inventory — every module-level tunable, with the code path it guards  [UNREACHABLE]

- **Location**: `android-shell/app/src/main/assets/zcode-protocol.js:46-215` (declarations),
  readers cited per row.
- **Symbol**: see table.
- **Why dead**: this is the full inventory the audit asked for — 45 module-level `var` declarations
  in the constants region (`:46-215`), grouped into 35 rows below. **22 of the 45 have no live
  reader**: every read sits inside either the sealed conversation block
  (`CONVERSATION_SUBSCRIBE_ENABLED=false`) or the active/burst section that no production code calls
  (F6). The seals that are findings in their own right: `MAX_REOPENS_PER_BRIDGE` (F2),
  `BURST_YIELD_*` and `DEFAULT_CLIENT_HELLO` (F6/F8), `sessionsByKeyCache` (F7, not a constant but in
  the same region), and the `CONVERSATION_*` family (Known/intentional — not re-reported as dead).
- **Reference search**: `^    var ` over `zcode-protocol.js:40-215` → 45 constant declarations;
  each name then grepped as `\bNAME\b` over `android-shell/app` + `android-shell/tools` (ripgrep; the
  per-family sweeps returned 74 + 216 + 40 + 31 + 16 hits, each reviewed individually).
- **Confidence**: high.
- **Suggested action**: keep but annotate (table is the evidence base for F2/F6/F7); delete with the
  blocks they seal.
- **Risk if removed**: none of these constants is read by Kotlin or by `inject.js`; only the test
  suite pins behaviour (see per-row "pinned by" column).

| # | line | constant | value | live reader? | verdict / pinned by |
| --- | --- | --- | --- | --- | --- |
| 1 | 46 | `MAX_MESSAGE_BYTES` | 16 MiB | yes (489, 558, 2842) | live; mirrors Kotlin `RelayWire` |
| 2 | 55 | `MAX_FRAGMENT_BYTES` | 1 MiB | yes (494, 499-500, 567) | live (exported too) |
| 3 | 56 | `MAX_FRAGMENTS` | 1024 | yes (495, 556, 2841) | live |
| 4 | 57 | `MAX_LOGICAL_FRAGMENTS` | 1024 | yes (845) | live (passive SI path) |
| 5 | 58 | `MAX_CONTAINER_ITEMS` | 100000 | yes (450) | live |
| 6 | 59 | `MAX_VALUE_BYTES` | 16 MiB | yes (435, 443, 461) | live |
| 7 | 62 | `REQ_PROMISE` | 100 | yes (755, 2505) | live |
| 8 | 63 | `REQ_PROMISE_CANCEL` | 101 | yes (2519) | constant live; **its export is dead** → F12 |
| 9 | 64 | `REQ_EVENT_LISTEN` | 102 | yes (773, 2523) | live |
| 10 | 65 | `REQ_EVENT_DISPOSE` | 103 | only 786 (inside `ChannelClient.addEventListener`) | **active-section only** → F6 |
| 11 | 66-70 | `RES_INITIALIZE` / `RES_PROMISE_SUCCESS` / `RES_PROMISE_ERROR` / `RES_PROMISE_ERROR_OBJ` / `RES_EVENT_FIRE` | 200/201/202/203/204 | 683, 698, 713, 716, 721, 2576, 2587, 2596, 2611 | live in `_observeInboundRpc`; the `ChannelClient.handleMessage` consumers (683-724) are active-section only → F6 |
| 12 | 73 | `CHANNEL_CONVERSATION` | `'zcode-agent'` | none in production | **no live reader** (all 7 uses are active/`start()`/conversation paths) → part of F14 TEST-ONLY |
| 13 | 74 | `EVENT_SESSIONS_INDEX` | `'onDynamicSessionsIndexFrame'` | yes (2526) | live |
| 14 | 75/76/77 | `METHOD_SUBSCRIBE_SI` / `METHOD_UNSUBSCRIBE_SI` / `METHOD_RESYNC_SI` | `…V4` | none in production | active-section only → F6 |
| 15 | 85 | `EVENT_CONVERSATION_FRAME` | `'onDynamicConversationFrame'` | only 1931 | sealed block (known/intentional) |
| 16 | 86 | `METHOD_SUBSCRIBE_CONVERSATION` | `'subscribeConversationV4'` | only 1905 | sealed block (known/intentional) |
| 17 | 91 | `CONTROLLER_TASKS_TOPIC` | `'controller/tasks-index'` | yes (2588) | live (third run-state source) |
| 18 | 93 | `CONVERSATION_MAX` | 2 | only 1803 | sealed block; test pins it (protocol.test.js:1210) |
| 19 | 95 | `CONVERSATION_SUBSCRIBE_MS` | 12000 | only 1905, 2102 | sealed block |
| 20 | 112 | `CONVERSATION_SUBSCRIBE_ENABLED` | **false** | 1879 (guard) | **known/intentional keep** (see below) |
| 21 | 122 | `METHOD_RESYNC_CONVERSATION` | `'resyncConversationV4'` | only 2102 | sealed block |
| 22 | 124 | `CONVERSATION_WATCHDOG_MS` | 10000 | only 2024 | sealed block |
| 23 | 126 | `CONVERSATION_QUIET_MS` | 20000 | only 2037 | sealed block; test pins (1155) |
| 24 | 128 | `CONVERSATION_RESYNC_MIN_GAP_MS` | 20000 | only 2043 | sealed block; test pins (1183) |
| 25 | 130 | `CONVERSATION_RESYNC_MAX_STRIKES` | 5 | only 2040 | sealed block; test pins (1191) |
| 26 | 132 | `CONVERSATION_ACTIVE_TEXT_MS` | 120000 | only 2057 | sealed block |
| 27 | 134 | `TERMINAL_PHASES` | 7 terminal phase names | **0 live readers** | **F11 NO-REFS** (both readers 1836 / 2065 are in the sealed block) |
| 28 | 152 | `sessionsByKeyCache` | `{}` | written 1778 (live), read 1828 / 2060 only | **F7 WRITE-ONLY** |
| 29 | 157-159 | `PAGE_RPC_SLOW_MS` / `PAGE_RPC_SLOW_LOG_MAX` / `PAGE_RPC_METHODS_MAX` | 1000 / 20 / 6 | 1189+2393, 2396+2400, 2444 | live (page-RPC tracing) |
| 30 | 164 | `PAGE_RPC_PENDING_MAX` | 200 | yes (2348) | bound live, but its stated justification (the burst waiting out its cap, comment 161-163) is gone → F6 note |
| 31 | 169 | `PAGE_RPC_SILENCE_MS` | 30000 | yes (2424) | live (feeds `onPageRpcSilence` → `inject.js:839`) |
| 32 | 185 | `MAX_REOPENS_PER_BRIDGE` | **0** | only 1178 (constructor default) | **F2 UNREACHABLE seal** |
| 33 | 196/197 | `FAULT_COOLDOWN_CONNECTIONS` / `FAULT_COOLDOWN_MS` | 3 / 10 min | only 1436 / 1441-1443 | **F4 UNREACHABLE** (only read by `_noteFault`, itself only called from the inert `_handleDegraded`) |
| 34 | 204/205 | `BURST_YIELD_TOTAL_MS` / `BURST_YIELD_POLL_MS` | 8000 / 120 | only 2228 / 1255 | **F6** (the removed subscribe-everything burst) |
| 35 | 211 | `DEFAULT_CLIENT_HELLO` | `{protocolVersion:3, appVersion:'3.6.5', clientKind:'mobileApp'}` | only 1639 (dead path) + dead export | **F8 WRITE-ONLY** |

---

### F2. `MAX_REOPENS_PER_BRIDGE = 0` seals the whole reopen-escalation branch  [UNREACHABLE]

- **Location**: `android-shell/app/src/main/assets/zcode-protocol.js:185` (value),
  `:1478-1489` (sealed branch), `:2127` (`_scheduleReopen` declaration).
- **Symbol**: `MAX_REOPENS_PER_BRIDGE`, `_scheduleReopen`, `_faultCounts`.
- **Why dead**: `_maxReopens` takes the constant unless a caller injects
  `options.maxReopensPerBridge` (`:1177-1178`) — and no production caller passes it. In
  `_handleDegraded` the counter is bumped to 1 (`:1477`) and then tested `1 > 0` (`:1478`), so the
  branch always terminates at `:1485` (`_forgetBridge` + `return`). **Line 1489
  `this._scheduleReopen(key, 1)` can never execute**, and with it the exponential backoff body
  (`:2127-2164`, `attempt > 3` give-up branch at `:2138`, the 2 s/4 s/8 s/16 s delays at `:2144`) and
  the self-recursion at `:2161`. (The whole enclosing chain is additionally inert — see F4 — so this
  is a seal inside a seal; the constant is the reachable-from-test seal.)
- **Reference search**: `\bMAX_REOPENS_PER_BRIDGE\b|_maxReopens|maxReopensPerBridge` over
  `android-shell/**` → 5 hits: decl `:185`, default `:1178`, read `:1478`, comment `README.md:453`
  ("现行值 0"), and tests `protocol.test.js:664,704` (which *assert* the non-reopen behaviour with
  `maxReopensPerBridge: 0`). `_scheduleReopen` → `zcode-protocol.js:2127,2140-2161` only; no
  `inject.js` / Kotlin hit.
- **Why the other hits do not count**: `README.md:453` documents the value as intentional, not as a
  caller; the test site pins the *sealed* behaviour (no reopen) — it never exercises line 1489.
- **Confidence**: high.
- **Suggested action**: delete `:1478-1489`'s second branch + `_scheduleReopen` + `_faultCounts`
  together with the active section (F6), or keep but annotate. `MAX_REOPENS_PER_BRIDGE` itself is
  kept alive only by dead code.
- **Risk if removed**: `protocol.test.js:662-684` ("the first fault on a workspace is not reopened
  (the shipped default)") and `:685-740` (cooldown) must be rewritten; `docs/18` §3.9④ listings and
  `README.md:453` ④ must be updated. No Kotlin coupling. This is a documented next-round candidate
  (`docs/18` §3.9④ names `MAX_REOPENS_PER_BRIDGE=0` explicitly).

---

### F3. `RemoteClient.acceptPayload` is a production no-op — all five routing tables are permanently empty  [UNREACHABLE]

- **Location**: `android-shell/app/src/main/assets/zcode-protocol.js:1357-1409` (body),
  called live from `inject.js:1097`.
- **Symbol**: `RemoteClient.prototype.acceptPayload`.
- **Why dead**: every branch reads a table that only the (never-called) active section fills:
  `_bridgesById` / `_pendingBridgePayloads` / `_inflightOpens` / `_pending` / `_bridges` are written
  **only** in `openBridge` (`:1544-1546, :1581-1582`) and `_request` (`:1341`, reached from
  `openBridge`/`listWorkspaces`). Therefore, on every inbound frame in production:
  `rpc-frame`/`rpc-frame-ack` (`:1362-1389`) misses the bridge, misses the per-id buffer, sees
  `_inflightOpens === 0` and returns — the 128-entry catch-all buffer at `:1381-1387` is unreachable;
  `bridge-degraded` (`:1390-1392`) enters `_handleDegraded`, which iterates an empty `_bridges`
  (F4); the request-matching loop at `:1395-1408` iterates an empty `_pending`. Net effect: the call
  inject.js makes on **every** inbound relay frame can neither log, write, nor resolve anything.
- **Reference search**: `_bridgesById|_pendingBridgePayloads|_inflightOpens|this\._pending\[|this\._bridges\[`
  over `android-shell/app` + `android-shell/tools` → 216 hits reviewed; every *write* is inside
  `openBridge`/`_request`/`dispose`; `\.(openBridge|listWorkspaces|start)\(` over `app/src/main` →
  0 hits outside `zcode-protocol.js`.
- **Why the other hits do not count**: the many `_bridges[...]` reads are in
  `_emitStatus`/`_handleDegraded`/`_dropRedundantBridge`/`_forgetBridge` (all downstream of the same
  empty map) and in `tools/protocol.test.js` (which calls `start()` — the test seam).
- **Confidence**: high (mechanism proven; the "no caller" half is the same census as the Scope
  section).
- **Suggested action**: keep but annotate (it is the wire router the Kotlin `RelayBridge` mirrors),
  or delete with the active section. Do **not** delete alone — inject.js still calls it.
- **Risk if removed**: would require an `inject.js` companion edit (drop the `target.acceptPayload`
  call at `inject.js:1097`); no test pins `acceptPayload` directly except through `start()`.

---

### F4. `_handleDegraded` + cross-connection cooldown + `_noteFault` can never run  [UNREACHABLE]

- **Location**: `android-shell/app/src/main/assets/zcode-protocol.js:1447-1491` (`_handleDegraded`),
  `:1412-1415` (`_inCooldown`), `:1430-1445` (`_noteFault`), state at `:1161-1165, :1179`.
- **Symbol**: `_handleDegraded`, `_inCooldown`, `_noteFault`, `_faultStreak`, `_cooldownUntil`,
  `_faultedInConnection`, `_pageOwned`.
- **Why dead**: `_handleDegraded`'s entire body is the loop `for (var key in this._bridges)`
  (`:1453`) — and `_bridges` can never be non-empty without `openBridge` (F3/F6). Consequences:
  the page-owned give-up log (`:1467`), the cross-connection cooldown (`:1441`) and the
  "abandon reopen on this connection" log (`:1484`) are unreachable; `_noteFault`'s only caller is
  `:1472` inside that loop, and `_inCooldown`'s callers are `:1432` (inside `_noteFault`), `:2148`
  (dead `_scheduleReopen`) and `:2190` (dead `start`). `FAULT_COOLDOWN_CONNECTIONS` /
  `FAULT_COOLDOWN_MS` (`:196-197`) therefore have no live reader.
- **Reference search**: `_handleDegraded|_noteFault|_inCooldown|_faultStreak|_cooldownUntil|_faultedInConnection|_pageOwned`
  over `android-shell/**` → all hits inside `zcode-protocol.js` (`:1412-1491`, `:2147-2148`,
  `:2197`, `:1218-1220` shared-state export) plus `tools/protocol.test.js:610-780` and
  `README.md:453` ③④. No `inject.js` hit, no Kotlin hit.
- **Why the other hits do not count**: the test hits call `client.start()` and hand-seed
  `_bridges`/`_bridgesById` (`protocol.test.js:615,873`) — the seam, not production; the README
  paragraph describes the mechanism as current, which is why this is worth reporting.
- **Confidence**: high.
- **Suggested action**: delete `_handleDegraded` + `_noteFault` + `_inCooldown` + the four state
  maps + both constants (keep `acceptPayload`'s `bridge-degraded` dispatch only if `Bridge` is
  kept); alternatively keep but annotate.
- **Risk if removed**: `protocol.test.js:610-740` (three tests) and the shared-state key list at
  `:1132-1133, :1218-1220` need updating; `README.md:453` ③④ must be rewritten. Documented
  next-round candidate (`docs/18` §3.9④, "active 段 … 生产跑不到").

---

### F5. `_dropRedundantBridge` / `_forgetBridge` / `_subs` never execute — the "give way to the page" path is inert  [UNREACHABLE]

- **Location**: `:2464-2483` (`_dropRedundantBridge`), `:2275-2283` (`_forgetBridge`),
  call site `:2547` (inside `_observeOutboundRpc`, which *is* live).
- **Symbol**: `_dropRedundantBridge`, `_forgetBridge`, `_subs`, `_activeKeys`.
- **Why dead**: `_dropRedundantBridge` returns at `:2466` because `this._bridges[key]` is always
  `undefined` (F3) — so its log line `页面已接管 …，关闭重复 bridge` (`:2482`) and the whole
  subscription-disposal branch (`:2469-2480`) can never run. Transitively **no production path
  reaches `_forgetBridge` at all**: its four call sites are the unreachable loop body (`:1468`),
  dead `_scheduleReopen` (`:2140`), dead `_openAndSubscribe` (`:2268`) and the unreachable tail of
  `_dropRedundantBridge` (`:2481`). `_subs` (`:2262-2263` write, `:2469-2471` read) can therefore
  never be non-empty, and `_activeKeys` (`:1105, :2255, :2278, :2880`) only ever receives writes
  from dead code.
- **Reference search**: `_dropRedundantBridge|_forgetBridge|_subs\b|_activeKeys` over
  `android-shell/**` → all hits inside `zcode-protocol.js`; `_dropRedundantBridge` appears only at
  `:2464` (decl) and `:2547` (call); `_forgetBridge` only at `:2275` + 4 call sites + comments
  `:2207, :1273`; plus `protocol.test.js:535-556, 615-693, 750-767` (which seed `_bridges` by hand
  via `start()`).
- **Why the other hits do not count**: `docs/18` §3.9④ claims "`_forgetBridge` 虽在段内但**仍活着**
  （`_dropRedundantBridge`/`_handleDegraded` 调它）" — that claim is **no longer true** after D7 was
  deleted: both callers are themselves unreachable now (this finding + F4). That is the added value
  here.
- **Confidence**: high.
- **Suggested action**: delete with F4/F6; if `Bridge` is kept for tests, keep `_forgetBridge` as
  part of that keep-set and annotate it as test-only.
- **Risk if removed**: `protocol.test.js:541-560` ("a bridge is dropped once the page proves it
  streams that workspace") and `:501-540` must be deleted/rewritten; `docs/18` §3.9④'s liveness note
  needs correcting.

---

### F6. The whole `// ---- active` burst is superseded by the Kotlin carrier and reachable only from tests  [SUPERSEDED]

- **Location**: `:2109-2283` (the active section, with its own header comment admitting "这一段没有
  生产调用者"), plus its support layer: `_request` `:1333-1354`, `listWorkspaces` `:1493-1518`,
  `openBridge` `:1520-1596`, `_releaseInflight` `:1603-1630`, `subscribeSessionsIndex`
  `:1636-1736`, `_resyncSessionsIndex` `:1738-1754`, `Bridge` `:996-1048`, `ChannelClient`
  `:633-789`, `RpcFrameSender` `:476-521`, `RpcFrameAssembler` `:526-621`, `awaitPageIdle`
  `:1247-1259`, `inFlightPageRpcs` `:1234-1239`, `maxWorkspaces` `:1082`, `_maxWorkspaces` read
  `:2187`.
- **Symbol**: `start`, `_openAndSubscribe`, `openBridge`, `subscribeSessionsIndex`,
  `listWorkspaces`, `_request`, `_releaseInflight`, `Bridge.attach/acceptPayload`,
  `ChannelClient.*`, `RpcFrameSender.sendMessage`, `RpcFrameAssembler.acceptPayload`,
  `awaitPageIdle`, `inFlightPageRpcs`.
- **Why dead / superseded**: `start()` is the D7 "subscribe every workspace" burst; D7's switch was
  deleted on 2026-09-17 (`docs/18` §3.9①, `inject.js:1029-1031, :844-846, :897`) and the
  multi-workspace job now belongs to the **native** carrier — `Tier2Probe.startCoverage` /
  `BridgeManager(maxWorkspaces = Tier2Probe.DEFAULT_MAX_COVERAGE = 5)` with the 12 s rotation
  (`docs/18` §3.8/§3.9②, `RelayBridge.reanchorProgress`, `RelayBridge.kt:1458-1459`
  "工作区列表（对应 JS listWorkspaces）"). The JS side is the replaced implementation: it opens
  bridges in a sequential burst with a shared 8 s yield budget (`:2228-2239`) — precisely the design
  §3.9② replaced. Everything it needs is unreachable in production: the `RpcFrameAssembler` (real
  reassembly + crc verify + ack) is instantiated only in `Bridge.attach:1018`, so production's only
  reassembler is the separate, ack-less `_tryAssemble` (`:2825`); `ChannelClient` is instantiated
  only at `:1032`; `_request`'s promise map only ever fills from `openBridge`/`listWorkspaces`.
  `BURST_YIELD_TOTAL_MS`/`BURST_YIELD_POLL_MS` (`:204-205`) and `awaitPageIdle` exist solely for the
  burst; `inFlightPageRpcs` has no non-burst caller (its own comment `:161-163` justifies
  `PAGE_RPC_PENDING_MAX` by the burst's cap — a stale justification, the bound itself is still
  wanted for the tracing map).
- **Reference search**: `\.start\(\)|openBridge|subscribeSessionsIndex|awaitPageIdle|inFlightPageRpcs|BURST_YIELD`
  over `android-shell/**` → `inject.js`: one comment (`:897`) only; `tools/protocol.test.js`: 15
  `client.start()` calls + `:788-815` (`inFlightPageRpcs`/`awaitPageIdle`); `fake-desktop.js:166`
  implements the desktop side of `subscribeSessionsIndexV4`; `zcode-protocol.js` self-references
  only. Kotlin: `RelayBridge.kt:1458-1459` names the JS twin as a *reference implementation*.
- **Why the other hits do not count**: `tools/*` is the Node seam the file's own header calls out
  ("loaded … AND required directly by the Node unit tests"); `inject.test.js` asserts the *absence*
  of writes ("默认配置下不主动开桥").
- **Confidence**: high on "no production caller" (proven); medium-high on the deletion advice (it is
  a documented deferral, see Risk).
- **Suggested action**: **keep but annotate** is the safe call; the honest one is delete-with-tests
  in a dedicated round (the codec/`ChannelClient` half is the wire-format reference the Kotlin
  `RelayWire`/`RelayBridge` cite — see `RelayBridge.kt:11`, `RelayWire.kt:9`).
- **Risk if removed**: `tools/protocol.test.js` loses ~20 of its 52 tests (active group at
  `:355-433, :469-560, :610-740, :780-815` + the `ChannelClient` group at `:212-268` and the
  `RpcFrameSender`/`RpcFrameAssembler` group at `:113-186`, which `tools/fake-desktop.js` also
  leans on); `docs/18` §3.9④ explicitly defers this to "下一轮" with a required real-device
  regression, so this is a **decision**, not a cleanup.

---

### F7. `sessionsByKeyCache` is written on every live sessions-index frame and read only by the sealed block  [WRITE-ONLY]

- **Location**: `:152` (declaration), `:1778` (write, inside live `_emitSessions`), readers `:1828`
  and `:2060`.
- **Symbol**: `sessionsByKeyCache`.
- **Why dead**: `_emitSessions` is called from both the passive path (`:2633`, per
  `_observeInboundRpc`, live: `inject.js:1099`) and the dead active path (`:1687`), and it always
  writes `sessionsByKeyCache[key] = list` (the list is sorted, so this is a real allocation per
  frame). Its only two readers are `_conversationCandidates` (`:1828`) and
  `_conversationLooksActive` (`:2060`) — both inside the `CONVERSATION_SUBSCRIBE_ENABLED=false`
  block. So in production the cache is filled forever and never read: a pure dead store with a
  per-frame cost, and the "跨 RemoteClient 实例存活" module-level design (`:144-151`) now buys
  nothing.
- **Reference search**: `\bsessionsByKeyCache\b` over `android-shell/**` → 4 hits: `:152`, `:1778`,
  `:1828`, `:2060`. No `inject.js`, no Kotlin, no test hit.
- **Confidence**: high.
- **Suggested action**: delete `:152` + the `:1778` write (the call is otherwise live and needed for
  `onSessions`) — i.e. **delete with the sealed block**; or keep but annotate if the conversation
  stream is coming back.
- **Risk if removed**: `protocol.test.js:1043-1049` / `:1202-1213` (`_conversationCandidates`) and
  `:1151-1200` (watchdog `_conversationLooksActive` path) construct their inputs by hand and do not
  use the cache directly, so they keep passing; no other test depends on it. Removing the write is a
  2-line change and does not break the wire format.

---

### F8. `_clientHello` + `DEFAULT_CLIENT_HELLO` are learned/declared but never consumed  [WRITE-ONLY]

- **Location**: `:211-215` (constant), `:1116` (field init), `:2508-2515` (live write, "learn the
  page's clientHello"), reads `:1639` (dead), export `:2896` (dead).
- **Symbol**: `DEFAULT_CLIENT_HELLO`, `RemoteClient._clientHello`.
- **Why dead**: the only reader is `subscribeSessionsIndex`'s `var hello = this._clientHello ||
  DEFAULT_CLIENT_HELLO;` (`:1639`), which is inside the never-called active section (F6). So the
  whole "observe the page's clientHello at runtime and prefer it over the fallback" mechanism — the
  feature the constant's comment (`:207-210`) and `_observeOutboundRpc` (`:2506-2515`) exist for —
  ends in a dead store. The export at `:2896` has zero readers as well (no test uses it).
- **Reference search**: `_clientHello|DEFAULT_CLIENT_HELLO` over `android-shell/**` → 8 hits, all in
  `zcode-protocol.js` (`:211, :1007, :1116, :1639, :2510, :2896`); 0 hits in `inject.js`, `tools/`,
  Kotlin.
- **Why the other hits do not count**: `:1007` is a *different*, unrelated field — `Bridge`'s own
  `_clientHello`, assigned `null` in the constructor and never touched again (fold it into this
  finding: pure dead field).
- **Confidence**: high.
- **Suggested action**: delete the write at `:2508-2515` + the field + the export; keep
  `DEFAULT_CLIENT_HELLO` only if the active section is kept.
- **Risk if removed**: none live. `protocol.test.js` authenticates through `fake-desktop.js`, which
  answers `initializeConversationV4` without asserting the hello contents; no assertion mentions
  either symbol.

---

### F9. Six counters incremented for nobody (and one never even incremented)  [WRITE-ONLY]

- **Location**: `:2353` (`_pageRpc.calls += 1`), `:2381` (`_pageRpc.errors`), `:2394`
  (`_pageRpc.slow`), `:1202` (`methodsLogged: 0`), `:2552` (`_obsOurs`), `:2555` (`_obsIn`),
  `:1114` (`_bridgeSeq = 0`).
- **Symbol**: `_pageRpc.calls`, `_pageRpc.errors`, `_pageRpc.slow`, `_pageRpc.methodsLogged`,
  `_obsOurs`, `_obsIn`, `_bridgeSeq`.
- **Why dead**: these are dead stores — incremented on live code paths but read nowhere. The
  *window* counters (`windowCalls/windowErrors/windowSlow`, read and reset in
  `reportPageRpcWindow:2433-2455`) are the ones that actually reach the log line; the cumulative
  trio is never surfaced (a "totals since boot" line was evidently planned and never added).
  `methodsLogged` is initialized and never read or written again. `_obsOurs`/`_obsIn` are the
  "inbound frames belonging to our bridge vs. the page's bridge" counters — the very distinction
  that matters only while our own bridges exist (F3/F6), and nothing reads them.
  `_bridgeSeq` is never used at all (`RpcFrameSender` has its own `_messageSeq`).
- **Reference search**: `_pageRpc\.(calls|errors|slow|methodsLogged)|_obsOurs|_obsIn|_bridgeSeq` over
  `android-shell/**` → `:2353, :2381, :2394, :1202, :2552, :2555, :1114` only (7 hits, each a single
  write or the initializer); no read anywhere, no test hit.
- **Confidence**: high.
- **Suggested action**: delete the 7 statements/fields (or surface them in `reportPageRpcWindow` if
  the totals were the point).
- **Risk if removed**: none; `protocol.test.js:561-609` asserts on the *window* summary log only.

---

### F10. `SessionsIndexState.workspaceId` / `logEpoch` / `ready` are write-only, and the gap-recovery reader was only ever wired into the dead path  [WRITE-ONLY]

- **Location**: `:817` / `:894` (`workspaceId`), `:818` / `:895` / `:1745-1746` (`logEpoch`),
  `:821` / `:932` / `:1691` (`ready`), `:822` / `:910` / `:1696-1697` (`needsResync`).
- **Symbol**: `SessionsIndexState.workspaceId`, `.logEpoch`, `.ready`, `.needsResync`.
- **Why dead**: `workspaceId` is assigned from the snapshot and then never read by anyone — not by
  this file, not by `_emitSessions` (whose payload at `:1783-1790` carries
  `key/title/workspacePath/workspaceIdentity/source/sessions`), not by `inject.js`, not by Kotlin
  (`ShellRuntime.onSessions` reads only `key/title/workspacePath/workspaceIdentity/source` +
  per-task `sessionId/title/phase/preview/pendingInteractionId/lastActivityAt/hasBackgroundWork`,
  `ShellRuntime.kt:1636-1664`). It mirrors the Kotlin field that `docs/18` §2.1 already deleted
  (`core/SessionsIndexState.kt` `workspaceId`) — the JS twin was missed. `logEpoch` is read only at
  `:1745-1746` (`_resyncSessionsIndex`, reached only from the dead active listener at `:1698`), and
  `ready` only at `:1691` (same dead listener, gating the one-shot conversation re-subscribe).
  **Wiring concern (behavioural, not just dead):** `needsResync` is set by the decoder at `:910` and
  read/cleared **only** in that dead listener (`:1696-1699`); the live passive consumer
  (`:2632-2636`) calls `state.applyWireFrame(data)` and ignores the flag. So in production a
  sessions-index sequence gap is detected, swallows the delta batch, and nothing ever resyncs — the
  comment at `:908-909` ("the caller must resync") describes a contract only the dead caller
  honours. `SessionsIndexState.seq` stays live (`:888, :907`), so the gap is still *detected*; only
  the recovery is orphaned.
- **Reference search**: `workspaceId|\.ready\b|logEpoch|needsResync` over `android-shell/**/*.js` →
  63 hits; `workspaceId` hits in JS are all `workspaceIdentity` (different symbol) or the two
  assignments at `:817/:894`; `state.ready`/`state.logEpoch`/`state.needsResync` reads exist only at
  `:1691, :1745, :1696`; Kotlin's `needsResync`/`logEpoch` hits are its own
  `core/SessionsIndexState.kt` (native carrier path, not this payload) and
  `tools/protocol.test.js:303` (asserts the flag is *set*).
- **Why the other hits do not count**: `tools/protocol.test.js:314-315` **builds** a snapshot with
  `workspaceId`/`logEpoch` to exercise the decoder — construction is not a read; `:303` asserts the
  flag, again not a consumer.
- **Confidence**: high for `workspaceId`/`logEpoch`/`ready` (write-only); high for the `needsResync`
  wiring gap (the passive call site is unambiguous).
- **Suggested action**: delete `workspaceId` (`:817, :894`); keep `logEpoch`/`ready`/`needsResync`
  **but decide the wiring**: either check `needsResync` in the passive path (`:2632`) or annotate it
  as "recovery only exists in the kept active section". Do not silently leave it.
- **Risk if removed**: `protocol.test.js:295-306` pins `needsResync` (must stay); the `workspaceId`
  deletion touches nothing.

---

### F11. `TERMINAL_PHASES` has zero readers outside the sealed conversation block  [NO-REFS]

- **Location**: `:134-142`.
- **Symbol**: `TERMINAL_PHASES`.
- **Why dead**: its two readers — `_conversationCandidates` (`:1836`) and `_conversationLooksActive`
  (`:2065`) — are both only reachable through `_subscribeConversationsBeforeIndex`, which returns at
  `:1879-1881` on the `CONVERSATION_SUBSCRIBE_ENABLED === false` guard. This is "dead matter inside
  the known-intentional block": a 9-line phase table + the `hasBackgroundWork || !TERMINAL_PHASES[…]`
  liveness rule that no longer has a live reader while the block stays sealed.
- **Reference search**: `\bTERMINAL_PHASES\b` over `android-shell/**` → 3 hits total: `:134`
  (declaration), `:1836`, `:2065`. Zero hits in `inject.js`, `tools/**`, Kotlin.
- **Confidence**: high.
- **Suggested action**: delete with the sealed block (do not delete alone — the two readers would
  then be undefined, and the block is a documented keep).
- **Risk if removed**: `protocol.test.js:1151-1200` (watchdog "leaves an idle conversation alone")
  exercises the `TERMINAL_PHASES` fallback indirectly through `_conversationLooksActive`; keep the
  table while those tests exist.

---

### F12. Three declarations and three exports with zero readers anywhere  [NO-REFS]

- **Location**: `:1207` (`isStarted`), `:614` (`purgeStale`), exports `:2890`
  (`REQ_PROMISE_CANCEL`), `:2896` (`DEFAULT_CLIENT_HELLO`), `:2917` (`MAX_FRAGMENT_BYTES`).
- **Symbol**: `RemoteClient.prototype.isStarted`, `RpcFrameAssembler.prototype.purgeStale`,
  `REQ_PROMISE_CANCEL` (export), `DEFAULT_CLIENT_HELLO` (export), `MAX_FRAGMENT_BYTES` (export).
- **Why dead**: `isStarted` has no caller in any layer — the only read of `_started` is `start()`
  itself (`:2175`), so both the flag and its getter belong to the dead section. `purgeStale`
  (declared "Drops assemblies that never completed") is called by nothing — `docs/18` §3.9④ already
  recorded this and deliberately kept it as an un-wired remedy; the residue is real but the decision
  was "keep". The three exports exist for the Node tests by the file's own header comment
  (`:2897`), yet no test reads them: `P.REQ_PROMISE` is used, `P.REQ_PROMISE_CANCEL` never;
  `DEFAULT_CLIENT_HELLO`/`MAX_FRAGMENT_BYTES` are never referenced as `P.*`.
- **Reference search**: `isStarted` over `android-shell/**` → 1 hit (`:1207`);
  `purgeStale` → 1 hit (`:614`); `P\.REQ_PROMISE_CANCEL|P\.DEFAULT_CLIENT_HELLO|P\.MAX_FRAGMENT_BYTES`
  over `android-shell/tools` → 0 hits (while `P.REQ_PROMISE`, `P.RES_*`, `P.EVENT_SESSIONS_INDEX`,
  `P.CHANNEL_CONVERSATION` do appear, which is what makes the three uniquely dead);
  `REQ_PROMISE_CANCEL` uses: `:63`, `:2519` (live constant use — **keep the constant**), `:2890`.
- **Why the other hits do not count**: `README.md:453` ⑤ mentions `awaitPageIdle`, not `isStarted`;
  `:1208` is the getter's own body.
- **Confidence**: high.
- **Suggested action**: delete `isStarted` (`:1207-1209`); delete `purgeStale` **or** wire it to the
  passive `_tryAssemble` table (which has the same unbounded-growth problem — see F13);
  delete the three export lines.
- **Risk if removed**: `protocol.test.js` has no assertion on any of the five; removing
  `purgeStale` re-opens the memory-leak question §3.9④ flagged (that is the argument for wiring it,
  not for keeping it unwired).

---

### F13. Half-built purge/ack plumbing: timestamps nobody purges, an ack nobody consumes, a field nobody reads  [VESTIGIAL]

- **Location**: `:585` + `:617` (`_assemblies[key].at` written / read by the dead `purgeStale`),
  `:854` (`SessionsIndexState._fragments[id].at`, never read), `:2852` (`_observedIn/_observedOut`
  assembly `at`, never read), `:540-542` (inbound `rpc-frame-ack` discarded), `:1021-1027`
  (ack send, active-section only), `:1305-1329` (`_emitStatus`, `bridges` count),
  `:804-805` (`parentSessionId`).
- **Symbol**: assembly `at` fields, `RpcFrameAssembler.acceptPayload`'s ack branch,
  `acceptPayload`'s `'rpc-frame-ack'` dispatch, `status.bridges`, `normalizeSession().parentSessionId`.
- **Why dead / vestigial**:
  1. **Three maps record `at` and never age anything out**: `purgeStale` (the only reader,
     `:614-621`) has zero callers (F12); the passive `_tryAssemble` table (`:2825-2871`) and
     `SessionsIndexState._fragments` (`:823, :848-870`) record `at` too but have no purge function at
     all. The timestamps are the residue of a sweep that was never wired — meanwhile both maps are
     genuinely unbounded, which is the leak `docs/18` §3.9④ describes.
  2. **The inbound ack branch is a dead protocol branch**: `:540-542` returns `true` for
     `zcode_type === 'rpc-frame-ack'` and drops `ackMessageSeq`; nothing anywhere tracks outstanding
     message seqs for retransmission. Repo-wide, `ackMessageSeq` has 3 *writes* (`:1025` here,
     `RelayBridge.kt:170` in the native carrier, `fake-desktop.js:118` in the test double) and one
     *read* — `RelayBridgeTest.kt:189`, a test. Kotlin discards inbound acks the same way
     (`RelayWire.kt:409`), so this is a shared, deliberate no-op rather than a JS-only gap.
  3. **`status.bridges` can only ever be `0`**: `_emitStatus` (`:1309-1314`) counts
     `this._bridges` (empty in production, F3), so the field is a constant. It is not cosmetic —
     Kotlin renders it: `ShellRuntime.kt:1700-1707` logs
     `订阅状态 bridges=$bridges passive=$passive (reason)`. The line therefore carries a permanently
     false-looking `bridges=0` and is a leftover of the deleted D7 status (its sibling `active` field
     was already removed, `ShellRuntime.kt:1697-1699`).
  4. **`parentSessionId`** is normalized (`:804-805`) but read by nobody on this path: `inject.js`
     forwards the payload verbatim, `ShellRuntime.kt:1644-1653` never reads it, and this file's only
     other consumer of session objects is the sealed `_conversationCandidates` (which reads
     `sessionId/phase/hasBackgroundWork/lastActivityAt`). `docs/18` §3.9④ already lists the Kotlin
     twin (`SessionEntry.parentSessionId`) as write-only.
- **Reference search**: `\.at\b` in `zcode-protocol.js` → `:585, :617, :854, :2344-2352, :2377,
  :2423, :2852` (the page-RPC ones at `:2344-2423` **are** read — leave them); `ackMessageSeq` over
  `android-shell/**` → 6 hits, none a retransmit consumer; `_emitStatus|"bridges"` → `:1305-1329`
  + `ShellRuntime.kt:1695-1711`; `parentSessionId` over `android-shell/app` → `zcode-protocol.js:804`
  only on the JS→native payload path.
- **Why the other hits do not count**: `tools/fake-desktop.js:118` (`this.acked.push(...)`) proves
  the *sender* works, not that anything consumes an inbound ack; `core/SessionsIndexState.kt:16/26/46`
  is the native carrier's own class, not this payload.
- **Confidence**: high for 1/2/3; high for 4 (with the Kotlin corroboration in `docs/18` §3.9④).
- **Suggested action**: delete the three `at` writes with the blocks; delete the `'rpc-frame-ack'`
  half of `:1362` and the ack branch `:540-542` **together with the active section** (the send side
  dies with it); drop `bridges` from the status object **with** a companion edit at
  `ShellRuntime.kt:1700/1703`; delete `parentSessionId` at `:804-805`.
- **Risk if removed**: item 3 needs the Kotlin log line changed in the same commit (otherwise the
  line reads `bridges=0` from a default); item 4 touches only `normalizeSession`, and
  `protocol.test.js:341-352` asserts `pendingInteractionId` (a *different* field) so it stays green.

---

### F14. The exported helper surface is used only by the Node tests  [TEST-ONLY]

- **Location**: `:2884-2918` (returned API), consumers `tools/protocol.test.js:19`,
  `tools/fake-desktop.js:14`, `tools/inject.test.js:25`.
- **Symbol**: `utf8Encode`, `utf8Decode`, `base64Encode`, `base64Decode`, `concatBytes`, `crc32`,
  `crc32Hex`, `ByteWriter`, `ByteReader`, `encodeValue`, `decodeValue`, `RpcFrameSender`,
  `RpcFrameAssembler`, `ChannelClient`, `SessionsIndexState`, `workspaceKeyOf`, `workspaceTitle`,
  `normalizeSession`, plus `RES_INITIALIZE`/`REQ_EVENT_LISTEN`/`RES_EVENT_FIRE`/`CHANNEL_CONVERSATION`
  and the `RemoteClient` class itself.
- **Why TEST-ONLY**: production loads this file as a page script and reads exactly one symbol
  (`inject.js:62` → `P.RemoteClient`); every other export is reached only from `tools/**`. This is
  the weakest category here and is *documented intent* (`:2897` "helpers (exported for the Node
  tests)") — reported for completeness, not as a deletion candidate.
- **Reference search**: `P\.[A-Za-z_]+` over `android-shell/tools` → 108 hits; every one of them is
  inside `tools/` (no `app/src/main` hit). `G\.ZcodeProtocol|ZcodeProtocol` over
  `app/src/main/assets` → 1 hit (`inject.js:62`).
- **Confidence**: high.
- **Suggested action**: keep (this is the seam the tests need); if the codec half is deleted with F6,
  these exports go with it and `tools/fake-desktop.js` (which builds its own fragmenter but reuses
  `ByteWriter`/`encodeValue`/`ByteReader`/`decodeValue`/`crc32Hex`/`base64Encode`/`concatBytes`)
  would need a private copy.
- **Risk if removed**: not applicable to a keep; deleting would break `fake-desktop.js`, which
  `inject.test.js` depends on.

---

## Known / intentional (not a new finding)

- **`CONVERSATION_SUBSCRIBE_ENABLED = false` self-subscribe conversation stream** (`:112`, guard
  `:1879-1881`, block `:1800-2107`) — kept per brief §5 / `docs/18` §5 ("被
  `CONVERSATION_SUBSCRIBE_ENABLED=false` 整体封死"); the 2026-09-15 real-device A/B reason (duplicate
  subscription squeezes the page's own receive stream; the HarmonyOS pure shell tracks fine) is still
  the recorded verdict and nothing in the tree contradicts it. **Re-check: reason still holds — yes.**
  Dead matter *inside* this keep, reported above rather than as new keeps: `TERMINAL_PHASES` (F11,
  zero live readers), the `sessionsByKeyCache` **readers** (F7 — the write is live!), and the
  fields/props with no live lifecycle: `_convSubs` (`:1912-1928`, test-seeded at
  `protocol.test.js:1158-1185`), `_convWatchdog` (`:2015-2018`, an interval only the sealed block
  can start), `bridge._convSubscribeTried` (`:1691-1692`, an undeclared property on `Bridge` whose
  only reader is the dead active listener), and the inject.js-side plumbing that feeds these
  functions and nothing else: `nativeRunningSessions` / `nativeRunningSessionsProvider`
  (`zcode-protocol.js:1812-1822` ← `inject.js:797, 801-808`) and the `config()`→`runningSessions`
  bridge (`WebAppBridge.kt:56`, `inject.js:787-788`) — the native carrier has its own live equivalent
  (`ShellRuntime.kt:460` → `Tier2Probe.runningSessionsProvider` → `RelayBridge.kt:1125`), so the JS
  copy is a vestige of the sealed design.
- **Active-section keep-for-tests** (`docs/18` §3.9④: "`zcode-protocol.js` 的 active 段 … 留给下一轮";
  `:2109-2125` says the same in-file) — **documented deferral, not in brief §5's list**, so it is
  reported as F6 rather than hidden here. Re-check: the reason (only end-to-end handshake
  implementation; tests use it as a seam) **still holds**, but the deferral is now two rounds old and
  every *caller* of it is gone; `docs/18` §3.9④'s sub-claim that `_forgetBridge` "仍活着" **no longer
  holds** (F5).
- **`RpcFrameAssembler.purgeStale`** (`docs/18` §3.9④: kept as an un-wired remedy; deleting it would
  "钉死泄漏") — reported as F12/F13 with the same conclusion: the intent holds, but note that the
  *passive* tables (which are the ones that actually grow, `:2825-2871`) never had a purge at all, so
  the remedy as kept protects only the dead path.
- **`Prefs.subscribeAllWorkspaces` (the D7 switch)** — brief §5 item, but Kotlin-only; the JS side of
  D7 is already fully removed (`:1083-1085` comment, `:2874` comment, no `subscribeAll` field:
  `protocol.test.js:392-403` asserts `client.subscribeAll === undefined`). **Nothing left to do in
  this file.**
- **`storeGet` / `storeSet`** (brief §5 note "删掉刷新实现后可能只剩个别调用方") — **verified: they are
  not in `zcode-protocol.js` at all.** They are `inject.js:1782` / `:1790` and still have live callers
  on the retained KICKED self-heal path (`inject.js:1027`, `:1224` → `:1315`, the only page-level
  intervention the read-only shell keeps). No action for this area. (One out-of-area lead for the
  inject.js auditor: `STALL_STORE_RELOADS` is written at `inject.js:2281` although the stall-reload
  implementation was deleted in `docs/18` §2.3 — I did **not** verify its readers, so it is not a
  claim.)
- **`SHELL_READ_ONLY` / `AUTO_TAKEOVER_ENABLED` / `SHELL_VISIBILITY_HIJACK`** (brief §5's three
  long-term constants) — none of the three appears in `zcode-protocol.js`
  (`grep SHELL_READ_ONLY|AUTO_TAKEOVER_ENABLED|SHELL_VISIBILITY_HIJACK` over this file → 0 hits), so
  no remaining code here is sealed by them.

## Suspected but unproven

- `SessionsIndexState.applyWireFrame`'s `kind === 'fragment'` logical-frame path (`:834-878`) —
  missing evidence: no field log in the repo pins whether the desktop ever delivers
  `sessions-index` as `kind:'fragment'`; `_trackConversationText`'s comment (`:2667-2675`) records
  `kind:'complete'` for conversation frames only. If the desktop always sends `complete`, this
  30-line branch is UNREACHABLE. Needs one packet trace or a temporary counter on the passive path.
- `CONVERSATION_SUBSCRIBE_ENABLED` is a **module-private** constant (`var`, not an option), so a
  runtime probe is impossible without an edit — which is why I did not try to prove the sealed block
  unreachable-by-observation (it is already unreachable by construction).
- `_trackConversationText`'s `data.rows` / `data.ops` fallbacks (`:2719-2723`) — `body` already
  falls back to `data` (`:2678-2680`), so the second half of each condition can only matter when
  `frame.payload` exists but carries no `rows`/`ops` while the top-level `data` does. Missing
  evidence: a captured frame in that shape; without it this is defensive redundancy, not dead code.
- `RES_PROMISE_ERROR_OBJ` (`:721-724`, branch in `ChannelClient.handleMessage`) — the branch is
  active-section-only (F6), but I cannot prove the desktop never emits type 203, so the branch itself
  is not claimed dead; only its only reader is.
- `_pageRpc.pending`'s bound (`PAGE_RPC_PENDING_MAX`, `:2348-2351`) — live and still needed to cap
  the tracing map, but its comment (`:160-164`) justifies it by the burst's idle-wait, which is gone
  (F6). Stale rationale rather than dead code.

## Summary

- **Findings: 14** — by tag: UNREACHABLE 5 (F1, F2, F3, F4, F5), SUPERSEDED 1 (F6), WRITE-ONLY 4
  (F7, F8, F9, F10), NO-REFS 2 (F11, F12), VESTIGIAL 1 (F13), TEST-ONLY 1 (F14).
- **Highest-value deletions**
  1. `zcode-protocol.js:1357` `RemoteClient.acceptPayload` + the five tables it routes
     (`_bridgesById`/`_pendingBridgePayloads`/`_inflightOpens`/`_pending`/`_bridges`) — the call
     `inject.js:1097` makes on every inbound frame is provably a no-op (F3).
  2. `zcode-protocol.js:1447` `_handleDegraded` + `_noteFault` (`:1430`) + `_inCooldown` (`:1412`) +
     `FAULT_COOLDOWN_*` (`:196-197`) — the fault/cooldown/give-way subsystem can never execute (F4),
     and `docs/18` §3.9④'s claim that `_forgetBridge` is still alive (`:2275`) is now false (F5).
  3. `zcode-protocol.js:1478-1489` + `:2127` — `MAX_REOPENS_PER_BRIDGE=0` seals the reopen escalation
     and `_scheduleReopen` entirely (F2); the highest-value *constant* seal outside the documented
     conversation keep.
  (Runners-up, cheap and independent of any deletion decision: `sessionsByKeyCache`'s write at
  `:1778` (F7), `TERMINAL_PHASES` at `:134` (F11), `isStarted` at `:1207` (F12).)
- **Estimated removable lines**: **~280 lines** deletable without touching the documented deferral
  (F2 ~50, F3 ~53, F4 ~72, F5 ~39, F7-F13 ~65). A further **~800 lines** (F6: the `// ---- active`
  section `:2109-2283`, plus `_request`, `listWorkspaces`, `openBridge`, `_releaseInflight`,
  `subscribeSessionsIndex`, `_resyncSessionsIndex`, `awaitPageIdle`, `inFlightPageRpcs`, `Bridge`,
  `ChannelClient`, `RpcFrameSender`, `RpcFrameAssembler`) become removable only if the
  keep-for-tests decision in `docs/18` §3.9④ is lifted — that is a decision with a required
  real-device regression, so it is not counted in the headline number. **Excluded from both**: the
  ~340-line conversation block (`:112-132, :152, :1800-2107`), a documented keep.
- **Test-suite context**: `node tools/protocol.test.js` → **52/52 pass** on the current tree (the
  brief's suggested `node --test tools/protocol.test.js` fails in this sandbox with `spawn EPERM`;
  running the file directly works). ~28 of the 52 tests sit on the surfaces in F5/F6, which is the
  concrete cost of that deletion.

---

## Status verification (2026-09-19)

**方法**：当前源码 `app/src/main/assets/zcode-protocol.js` 已从 2919 行降到 **1919 行**。先以当前源码
grep 重建「符号 → 行号」映射，再对本报告每个顶层条目判定三态（已删除 / 仍在·活着 / 仍在·仍死）。
「活着」的判据是逐条复核真实调用方：JS 内部活路径、`assets/inject.js`、Kotlin 侧
（`app/src/main/java/com/zcode/remote/**` 全量 grep）；grep 限定 `app/src`、`assets`、`tools/`，
未读 `docs/` 下任何参考副本。测试基线：`node tools/protocol.test.js` → **38/38 通过（0 fail）**，
测试数与四批清理口径一致（52→38）。清理批次统一记为 **b627610（4c，2026-09-18）**，其中对话自订阅簇
记为 **4c-B**。

### F1–F14 顶层判定

| 条目 | 原判定 | 当前状态 | 备注 |
| --- | --- | --- | --- |
| F1 常量清单（45 个 var / 35 行） | UNREACHABLE（清单性） | 部分已删 | 16/35 行已删（18 个常量，随封死块与桥路，b627610）；15 行仍活、4 行仍死。逐行见下表 |
| F2 `MAX_REOPENS_PER_BRIDGE=0` / `_scheduleReopen` / `_faultCounts` | UNREACHABLE | **已删除** | b627610（4c）。构造函数 :1025-1028 留有「恢复开桥必须整套加回」警示注释 |
| F3 `RemoteClient.acceptPayload`（五张路由表） | UNREACHABLE | **已删除** | b627610（4c）。inject.js:1069 同步删除调用点并留注释；五张表（`_bridges`/`_bridgesById`/`_pending`/`_pendingBridgePayloads`/`_inflightOpens`）全删 |
| F4 `_handleDegraded` / `_inCooldown` / `_noteFault` | UNREACHABLE | **已删除** | b627610（4c），连同 `_faultStreak`/`_cooldownUntil`/`_faultedInConnection`/`_pageOwned`（构造函数 :1025-1030 注释） |
| F5 `_dropRedundantBridge` / `_forgetBridge` / `_subs` / `_activeKeys` | UNREACHABLE | **已删除** | b627610（4c）。`_observeOutboundRpc` :1496-1503 在原调用点留恢复注释（含「页面已接管」日志不再期待的回归提示） |
| F6 active 段 + 支撑层 | SUPERSEDED（建议保留注记） | **部分已删** | 已删（b627610）：`start`/`openBridge`/`listWorkspaces`/`subscribeSessionsIndex`/`_request`/`_releaseInflight`/`_resyncSessionsIndex`/`_openAndSubscribe`/`Bridge`/`awaitPageIdle`。仍留：`RpcFrameSender`(:392)、`RpcFrameAssembler`(:442)、`ChannelClient`(:541)、`inFlightPageRpcs`(:1080)——:380-382 已按原建议注记「不在生产路径」，tests 仍以它们为接缝 |
| F7 `sessionsByKeyCache` | WRITE-ONLY | **已删除** | b627610（4c）。写点随 `_emitSessions` 重写消失（今 :1183/:1224 注释说明） |
| F8 `_clientHello` + `DEFAULT_CLIENT_HELLO` | WRITE-ONLY | **部分已删** | 学习写点 + 字段 + `Bridge._clientHello` 已删（4c，:1460-1462 注释）。常量仍在：声明 :115 + 导出 :1896，且原唯一内部死读者（subscribeSessionsIndex 的 :1639）也删了 → **全仓零读者，比原判定更死** |
| F9 六个无人读计数器 | WRITE-ONLY | **部分已删** | 已删：`_obsOurs`/`_obsIn`（:1507-1510 注释）、`_bridgeSeq`（:990-996 注释）。仍留：`_pageRpc.calls/errors/slow`（:1329/:1357/:1370，仍在活路上自增）与 `methodsLogged`（:1052）——**四个只写计数器原样未动** |
| F10 `SessionsIndexState.workspaceId/logEpoch/ready/needsResync` | WRITE-ONLY + 接线缺口 | **仍在（加重）** | 四者全在（:725-730、:827-828、:846、:873）；原死路径读者删净后全部成为纯只写。接线缺口的处置与原建议不同：4c 另起炉灶，新增 `stale`/`gapFromSeq`（:753-755、:850-851）+ `_emitSessionsStale`(:1226) + 被动路径跃迁上报（:1589-1627），原生经 `TaskStore.staleSince` 消费（TaskStore.kt:101、ShellRuntime.kt:2087-2090）——缺口从「无人管」变成「可观测 + 原生消费」；但 `needsResync` 本身仍是孤儿 |
| F11 `TERMINAL_PHASES` | NO-REFS | **已删除** | b627610（4c-B）随对话自订阅簇 |
| F12 `isStarted` / `purgeStale` / 三个死导出 | NO-REFS | **部分已删** | `isStarted` 已删；`purgeStale` 函数体已删（原「保留不接线」决定被 4c 推翻），但 :529 留下悬空 doc 注释；三个导出仍在且仍零读者：`REQ_PROMISE_CANCEL`:1890、`DEFAULT_CLIENT_HELLO`:1896、`MAX_FRAGMENT_BYTES`:1917（tools 的 `P.*` 普查复核：三者无一处出现）。常量本身（:63）仍活：:1466 读 |
| F13 半成品 purge/ack 管道 | VESTIGIAL | **大体已处理** | ③ `status.bridges`：**已删**（`_emitStatus` :1118-1128），Kotlin 同步停读（ShellRuntime.kt:2127-2131，原报告要求的配套修改已做）；① 三处 assembly `at`（RpcFrameAssembler :501、`_fragments` :787、`_tryAssemble` :1857）仍只写、仍无清扫；② RemoteClient 侧 `rpc-frame-ack` 派发随 acceptPayload 删净，RpcFrameAssembler 内 ack 分支（:456-458）随类保留（测试专用）；④ `parentSessionId`（:712-713）仍在，唯一 JS 读者 `_conversationCandidates` 已删（4c-B）→ 现在纯只写 |
| F14 导出测试面 | TEST-ONLY（保留） | **仍在（活着，测试专用）** | 导出面原样（:1885-1918）；`P.*` 普查 22 个符号全部来自 tools/；本判定维持 |

### F1 常量 35 行逐一核对

| F1 行 | 常量 | 原判定 | 当前状态 | 现行号 / 读者 |
| --- | --- | --- | --- | --- |
| 1 | `MAX_MESSAGE_BYTES` | 活着 | 仍在（活着） | :46；:405-416、:474、:1847 |
| 2 | `MAX_FRAGMENT_BYTES` | 活着（导出也活） | 仍在（活着） | :55；:483、:1917（导出本身仍无读者） |
| 3 | `MAX_FRAGMENTS` | 活着 | 仍在（活着） | :56；:411、:472、:1846 |
| 4 | `MAX_LOGICAL_FRAGMENTS` | 活着 | 仍在（活着） | :57；:778 |
| 5 | `MAX_CONTAINER_ITEMS` | 活着 | 仍在（活着） | :58；:354 |
| 6 | `MAX_VALUE_BYTES` | 活着 | 仍在（活着） | :59；:339/:347/:365 |
| 7 | `REQ_PROMISE` | 活着 | 仍在（活着） | :62；:663（ChannelClient）、:1459（活） |
| 8 | `REQ_PROMISE_CANCEL` | 活着（导出死） | 仍在（活着）；导出仍死 | :63；:1466（活）；导出 :1890 零读者 |
| 9 | `REQ_EVENT_LISTEN` | 活着 | 仍在（活着） | :64；:681（测试专用类）+ :1470（活） |
| 10 | `REQ_EVENT_DISPOSE` | active 段专用 | 仍在（仍死 / 测试专用） | :65；唯一读者 :694（ChannelClient.addEventListener，测试专用类） |
| 11 | `RES_INITIALIZE`…`RES_EVENT_FIRE` | 活着 | 仍在（活着） | :66-70；:591-629（测试专用类）+ :1568-1569（活） |
| 12 | `CHANNEL_CONVERSATION` | TEST-ONLY | 仍在（仍死 / 测试专用） | :73；文件内零读者；导出 :1887；tools 1 处 `P.CHANNEL_CONVERSATION` |
| 13 | `EVENT_SESSIONS_INDEX` | 活着 | 仍在（活着） | :74；:1473，导出 :1888，tools 6 处 |
| 14 | `METHOD_SUBSCRIBE_SI`/`UNSUBSCRIBE_SI`/`RESYNC_SI` | active 段专用 | 仍在（仍死，**加重**） | :75-77；`subscribeSessionsIndex` 已删 → 全仓零读者、未导出 |
| 15 | `EVENT_CONVERSATION_FRAME` | sealed block | **已删除** | 4c-B |
| 16 | `METHOD_SUBSCRIBE_CONVERSATION` | sealed block | **已删除** | 4c-B |
| 17 | `CONTROLLER_TASKS_TOPIC` | 活着 | 仍在（活着） | :89；:1545 → `_forwardControllerTasks`(:1155) → inject.js:820 → Kotlin |
| 18-26 | `CONVERSATION_MAX`/`_SUBSCRIBE_MS`/`_SUBSCRIBE_ENABLED`/`METHOD_RESYNC_CONVERSATION`/`_WATCHDOG_MS`/`_QUIET_MS`/`_RESYNC_MIN_GAP_MS`/`_RESYNC_MAX_STRIKES`/`_ACTIVE_TEXT_MS` | sealed block | **已删除** | 4c-B 整簇（含封死开关本身） |
| 27 | `TERMINAL_PHASES` | NO-REFS | **已删除** | 4c-B |
| 28 | `sessionsByKeyCache` | WRITE-ONLY | **已删除** | 4c |
| 29 | `PAGE_RPC_SLOW_MS`/`_SLOW_LOG_MAX`/`_METHODS_MAX` | 活着 | 仍在（活着） | :95-97；:1039、:1372/:1376、:1420 |
| 30 | `PAGE_RPC_PENDING_MAX` | 活着（理由陈旧） | 仍在（活着）；陈旧注释原样 | :102；:1324。:98-101 注释仍以 burst 说事 |
| 31 | `PAGE_RPC_SILENCE_MS` | 活着 | 仍在（活着） | :107；:1400 |
| 32 | `MAX_REOPENS_PER_BRIDGE` | F2 seal | **已删除** | 4c |
| 33 | `FAULT_COOLDOWN_CONNECTIONS`/`_MS` | F4 | **已删除** | 4c |
| 34 | `BURST_YIELD_TOTAL_MS`/`_POLL_MS` | F6 | **已删除** | 4c |
| 35 | `DEFAULT_CLIENT_HELLO` | WRITE-ONLY | 仍在（仍死，**加重**） | :115 声明 + :1896 导出；内部死读者与学习写点均删 → 全仓零读者 |

**F1 小计**：16/35 行已删（18 个常量）；15 行活；4 行仍死（行 10、12、14、35）。

### Known / intentional 区核对

| 条目 | 原判定 | 当前状态 | 备注 |
| --- | --- | --- | --- |
| `CONVERSATION_SUBSCRIBE_ENABLED=false` 自订阅对话流 | known/intentional keep | **已删除** | 4c-B 整块删除（原「保留」决定被推翻）；protocol.test.js 52→38 |
| active 段 keep-for-tests（docs/18 §3.9④ deferral） | documented deferral | **部分被推翻** | 全部调用方已删；编解码三件套按 F6 建议保留并在 :380-382 注记。原报告指出的 §3.9④「`_forgetBridge` 仍活着」错误说法随 F5 删除成为事实订正 |
| `RpcFrameAssembler.purgeStale` | keep as un-wired remedy | **已删除** | 函数体删，:529 悬空 doc 注释残留 |
| `Prefs.subscribeAllWorkspaces`（D7 开关） | 本文件无事可做 | 仍成立 | 本文件仅注释（:974-976、:1879） |
| `storeGet`/`storeSet` | 不在本文件 | 仍成立 | 零命中 |
| `SHELL_READ_ONLY`/`AUTO_TAKEOVER_ENABLED`/`SHELL_VISIBILITY_HIJACK` | 不在本文件 | 仍成立 | 零命中 |
| `nativeRunningSessions`/`nativeRunningSessionsProvider` | sealed-block vestige | **已删除** | inject.js:791-792 注释留痕；Kotlin 侧更早由 2c89b88 删除 |

### Suspected but unproven 区核对

- `applyWireFrame` 的 `kind:'fragment'` 逻辑帧路径（今 :760-811）——**仍在**，仍无桌面侧证据，维持「未证实」。
- `CONVERSATION_SUBSCRIBE_ENABLED` 运行时探针说明——**已过时**（常量随 4c-B 删除）。
- `_trackConversationText` 的 `data.rows`/`data.ops` 回退（今 :1716-1720）——**仍在**，仍属防御性冗余。
- `RES_PROMISE_ERROR_OBJ`——被动路径有活读者（:1569 → `_tracePageResult`）；ChannelClient 内分支（:629）随类保留（测试专用）。归入 F1 行 11。
- `PAGE_RPC_PENDING_MAX` 的 burst 注释（:98-101）——**陈旧理由注释原样保留**，未随清理修订。

### 新观察（原报告未列，2026-09-19 现状产生）

1. **`lastPageBridgeTrafficAt`（:1071）新晋测试专用**：原判定「活着」（inject.js:2126/2205 两处调用）；
   b627610 的 inject.js 改版把两处调用全删了，如今唯一读者是 tools/protocol.test.js:869-900。
   其写点 `_pageBridgeTrafficAt`（:1516）仍在活路上盖章。清理自身制造的新死代码。
2. **`_maxWorkspaces`（:973）新晋只写字段**：唯一读者 `start()` 已删。
3. **`dispose()`（:1878-1882）体被清空**：inject.js:861 仍调用（活），但 4c 删光重置语句只剩注释。
4. **四处悬空 doc 注释**：:529（purgeStale）、:1056（start）、:1137（acceptPayload）、:1139（backed-off）
   ——机械删除的残根，建议下一轮顺手扫掉。
5. **文件头注释过时**：:19-24 仍宣称「Two consumers: active … (D7)」，active 消费者已删；:944
   「见 start() 上的注释」、:79-84 对话订阅顺序注释同理。
6. **审计范围外新增的活代码**（2026-09-19）：`latestConversationTopic`(:1795)、`_convLastTopic`/
   `_convLastFrameAt`、`stale`/`gapFromSeq`/`_emitSessionsStale` 缺口陈旧标记链（JS → inject.js →
   `TaskStore.staleSince`）——均已在生产路径上。

### 小结

- 核对单元合计 **61**：F1 的 35 常量行 + F2–F14 共 13 条发现 + Known/intentional 7 条 + Suspected 5 条。
- **已删除**：6 条发现整条（F2/F3/F4/F5/F7/F11）+ F1 的 16 行 + Known 区 2 条，另在 F6/F8/F9/F12/F13
  中有 10 处局部删除（`start`/`openBridge`/`Bridge`/`_clientHello`/`_obsOurs` 等）。
- **仍活**：F1 的 15 行、F14 测试面，及各部分删除条目中保留的活路径（acceptObservedPayload 链、
  `_tracePageCall/_tracePageResult`、`stale` 链、controller-tasks 转发链）。
- **仍死（未删）**：F1 的 4 行（REQ_EVENT_DISPOSE、CHANNEL_CONVERSATION、METHOD_*_SI、
  DEFAULT_CLIENT_HELLO）、F9 的 4 个只写计数器、F10 的 4 个只写状态字段、F12 的 3 个死导出、
  F13 的三处 `at` 时间戳与 `parentSessionId`、F6 保留的测试专用件与 `inFlightPageRpcs`、
  以及新晋的 `lastPageBridgeTrafficAt`/`_maxWorkspaces`。
