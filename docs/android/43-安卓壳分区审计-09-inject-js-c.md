> ⚠️ Historical audit baseline (2026-09-18, pre-cleanup). Status as of 2026-09-19: see "Status verification" section at the end. Many items listed as dead have since been deleted in batches 1–4 (commit b627610).

# inject-js-c — dead-code findings

## Scope audited

- `android-shell/app/src/main/assets/inject.js` (3749 lines) — page-injected JS layer of the Android thin shell. **This agent owns lines 1995–2658 only.**
  - §5c 卡死看门狗 stall watchdog (1995–2328): `STALL_*` constants, `PAGE_LOG_*` tables, `stallState`, `readVitals`/`vitalsStalled`/`postPageVitals`, zombie fingerprint, `stallArm`/`stallFire`/`stallCancel`, `nudgeReconnect` (stub), `stallReloadIfAllowed` (stub), `conversationStreamStallTick`, `startConversationStreamMonitor`, `notePageLogEvent`.
  - §5d 诊断指令入口 (2330–2411): `relayKickTest`.
  - §5e degrade 实验 (2413–2467): `relayDegradeTest`.
  - unnumbered block A (2469–2571): `describeRelay`, `closeRelaySocketForNudge`, `G.__zcodeShellNudgeRecover`.
  - unnumbered block B (2573–2657): `G.__zcodeShellNetProbe`, `G.__zcodeShellAfterCarrierReturn`.
- Cross-checked (read-only, not audited as scope): `app/src/main/java/com/zcode/remote/{MainActivity,DiagReceiver,ShellRuntime}.kt`, `assets/zcode-protocol.js` (symbol-existence only), `tools/inject.test.js`, `README.md`, `docs/18-全仓审计-错误路线残留与清理.md` §4/§5.
- Baseline: `node --test tools/inject.test.js` → **45 pass / 0 fail** (run for this audit).

### Verified-negative results (state of the range, so the parent can trust the positives)

- **Every `diag_cmd` id reachable from §5d/§5e is still live wiring.** `MainActivity.runDiagCommand` (`MainActivity.kt:754-785`) handles only `passive_off`/`passive_on` itself; **everything else falls through** to `pendingDiag = cmd; tryDiag()` → `window.__zcodeShellDiag(...)` (`MainActivity.kt:782-806`). `DiagReceiver.onReceive` does the same via `ShellRuntime.dispatchJsDiag` (`DiagReceiver.kt:39-40`, `ShellRuntime.kt:1257-1273`). So `vitals`, `l1_test`, `kick_test`, `degrade_test`, `bg_state`, `bg_http[|url]`, `bg_redial[:mode]`, `deadlink_test`, `probe_composer`, `compose|…` are all still delivered to `inject.js:2898-2959`. All ten are documented in `README.md:1010-1024`. No orphan id found.
- **The §5e degrade experiment is still reachable** — `degrade_test` → `inject.js:2938-2940` → `relayDegradeTest()` (2425). Reachable from both adb channels only; **not** reachable from any automatic/production path. Not dead.
- **Its dependencies still exist**: `RemoteClient.prototype.pageBridgeSessionIds` (`zcode-protocol.js:1294`), `lastPageBridgeTrafficAt` (`:1225`), `lastPageConversationTrafficAt` (`:1229`), `NativeWebSocket`/`lastRelayUrl`/`lastAuthInitText` (`inject.js:252/259/260`). So **no** function in this range silently early-returns on a deleted probe — the watchdog patrol really does read live counters.
- **The two unnumbered blocks (2469–2657) are NOT dead**: `G.__zcodeShellAfterCarrierReturn` has a production caller (`ShellRuntime.kt:241-242`, carrier handback) and 2 tests; `G.__zcodeShellNudgeRecover` has a production caller (`ShellRuntime.nudgePageRecovery`, `ShellRuntime.kt:1100-1108`, reached from `nudge_now`/`nudge_close`, `ShellRuntime.kt:1199-1207`) and 2 tests. `describeRelay` is used by both plus the `bg_state` command. `closeRelaySocketForNudge` is covered by `inject.test.js:1728`.

---

## Findings

> The long-standing **deferral** of the whole §5c block is documented (`docs/18` §5 row 2) and is listed under "Known / intentional" below — *not* re-reported. The findings here are the **new dead matter the stub-out left behind**, quantified at the end.

### F1. `STALL_RELOAD_CAP` has no reader  [NO-REFS]
- **Location**: `android-shell/app/src/main/assets/inject.js:2017`
- **Symbol**: `STALL_RELOAD_CAP`
- **Why dead**: it bounded the deleted "连续刷新上限" logic. The only surviving mention of a cap is `stallState.reloadCount`, which is itself never read (F2).
- **Reference search**: `STALL_RELOAD_CAP` over `android-shell/` **and** over the whole `E:\Zcode_harmony` tree → 1 hit total (the declaration). Full-file grep on `STALL_` → 15 hits, all others accounted for below.
- **Confidence**: high
- **Suggested action**: delete
- **Risk if removed**: none; no test references it.

### F2. `stallState.reloadCount` + sessionStorage key `STALL_STORE_RELOADS`: written, read only into a field nobody reads  [WRITE-ONLY]
- **Location**: declaration `inject.js:2029` (`STALL_STORE_RELOADS = 'zcodeShellStallReloads'`); **read** `inject.js:2055-2061` (initializer `parseInt(G.sessionStorage.getItem(STALL_STORE_RELOADS), 10) || 0`); **write** `inject.js:2280-2281` (`stallState.reloadCount = 0; storeSet(STALL_STORE_RELOADS, '0')`).
- **Symbol**: `stallState.reloadCount`, `STALL_STORE_RELOADS`
- **Why dead**: the cap logic that consumed `reloadCount` was deleted with `stallReloadIfAllowed`'s body; the field is now assigned its initial `0` and reset to `0`, and **no expression ever reads it**. The storage key is therefore always `'0'`, and the value read at boot is stored into that never-read field — the sessionStorage round-trip is a no-op in both directions. (`storeSet`/`storeGet` both legitimately use `sessionStorage` — `inject.js:1782-1798` — so this is not a storage-mismatch bug, just dead state.)
- **Reference search**: `reloadCount` over `android-shell/` → 2 hits, both writes (2280; and the initializer at 2055). `zcodeShellStallReloads|STALL_STORE_RELOADS` over the whole `E:\Zcode_harmony` tree → 4 hits: 2029 (def), 2057 (read), 2281 (write), 0 elsewhere (no Kotlin, no test, no other JS). `reloadCount` never appears in `tools/`.
- **Confidence**: high
- **Suggested action**: delete with the boot-time `sessionStorage` read and the `storeSet` at 2281
- **Risk if removed**: none; nothing asserts on it. Note the leftover `'zcodeShellStallReloads'` key already sitting in users' sessionStorage becomes garbage — harmless (session-scoped).

### F3. `stallState.streamStalled` is never read or written  [NO-REFS]
- **Location**: `inject.js:2050`
- **Symbol**: `stallState.streamStalled`
- **Why dead**: vestige of an earlier "stream stalled" flag; the surviving equivalent is the local traffic-clock check inside `conversationStreamStallTick` (2292-2296). The field is initialised once and touched nowhere.
- **Reference search**: `streamStalled` over `android-shell/` → 1 hit (the literal at 2050). Full-file `stallState` grep (46 hits) inspected line by line: no `.streamStalled` read or write.
- **Confidence**: high
- **Suggested action**: delete
- **Risk if removed**: none.

### F4. `stallState.lastReloadAt` is a dead store — only a test writes it  [WRITE-ONLY]
- **Location**: `inject.js:2048` (declaration, `lastReloadAt: 0`); sole write anywhere: `tools/inject.test.js:1456` (`FB().stall().lastReloadAt = Date.now() - 16000;`)
- **Symbol**: `stallState.lastReloadAt`
- **Why dead**: it existed to enforce the deleted "15s 刷新间隔". No production expression reads it. Note this is a **different field** from `fallbackState.lastReloadAt` (`inject.js:1688`) — the short name collides, which is why the file-wide grep needs care.
- **Reference search**: `lastReloadAt` over `android-shell/` → 3 hits: `inject.js:1688` (`fallbackState`, live-ish sibling cluster, different object), `inject.js:2048` (this declaration), `inject.test.js:1456` (write). Zero reads of `stallState.lastReloadAt`.
- **Confidence**: high
- **Suggested action**: delete with the test line at `inject.test.js:1456` (that line simulates a state the read-only shell can no longer reach; removing it changes no assertion — the test at 1439-1470 asserts `reloads === 0` unconditionally)
- **Risk if removed**: test edit required, assertion outcome unchanged.

### F5. `stallFire`'s 180s rate-limit branch is now unreachable — its clock lost its only writer  [UNREACHABLE]
- **Location**: branch `inject.js:2208-2216`; clock declared `inject.js:1156` (`var lastForcedReconnectAt = 0;`); constant `inject.js:1132` (`RECONNECT_MIN_GAP_MS = 180000`)
- **Symbol**: `lastForcedReconnectAt`, `RECONNECT_MIN_GAP_MS`, the `else if` at 2208
- **Why dead**: `stallFire` decides whether a nudge was too recent with `Date.now() - lastForcedReconnectAt <= RECONNECT_MIN_GAP_MS`. The **only writer** of `lastForcedReconnectAt` was the body of `forceReconnect`, which the 2026-09-16 audit replaced with a guard + log stub (`inject.js:1494-1497` — it now only calls `noteReadOnlyRefusal('重建页面 socket', reason)` and returns `false`). The variable is therefore permanently `0`, `Date.now() - 0` is ~1.7e12 ≫ 180000, so the condition is **always false after 1970** and the branch (including its carefully-documented "这里以前缺了一道门" rationale at 2209-2213) can never execute. Control always falls to the `else` at 2217-2219.
- **Reference search**: `lastForcedReconnectAt` over `android-shell/` → 5 hits: 1156 (declaration), 2208 (this read), plus comments at 1171/1173 (`README.md:454` quote) and 2210/2241 — **zero assignments anywhere in the repo**. `forceReconnect` → `inject.js:1494` (stub definition), 1464 (`heartbeatTick`), 3006 (foreground handler), 353 / 1171 / 1173 (comments).
- **Confidence**: high
- **Suggested action**: delete the branch `2208-2216`; then `RECONNECT_MIN_GAP_MS` (1132) and `lastForcedReconnectAt` (1156) lose their last reader and should go too. Behaviour is unchanged either way, because the surviving `else` calls `nudgeReconnect`, which is a log-only stub.
- **Risk if removed**: none functionally. Do **not** delete `noteReadOnlyRefusal` / `lastReadOnlyRefusalAt` (`inject.js:1190-1203`) — `forceReconnect` and `nudgeReconnect` both still use it, and `inject.test.js:1403-1404` pins its log line.

### F6. `stallState.armReason` survives only to decorate a refusal log  [VESTIGIAL]
- **Location**: written `inject.js:2166`; read `inject.js:2218` (`nudgeReconnect(stallState.armReason || '页面无进展')`)
- **Symbol**: `stallState.armReason`
- **Why dead-ish**: its single reader forwards it into `nudgeReconnect` (2244-2250), whose whole body is `noteReadOnlyRefusal('轻推页面 socket', reason); return false;`. So the string ends up in a throttled log line ("本会触发的原因：…") and nowhere else. The reason string is also already emitted verbatim one line earlier at 2171 (`'卡死看门狗布防（' + reason + '）'`), so on the arm→fire path the same text is logged twice.
- **Reference search**: `armReason` over `android-shell/` → 4 hits: 2046 (init), 2166 (write), 2218 (only read), 0 in tests/Kotlin.
- **Confidence**: high (mechanism), medium as a *deletion* candidate
- **Suggested action**: keep but annotate, or fold into F9's module deletion. Do not delete alone — `stallFire` still needs the fallback `'页面无进展'` string if the module stays.
- **Risk if removed**: only a log line changes.

### F7. `postPageVitals` + the native `pagevitals` handler exist only for the watchdog  [VESTIGIAL / cross-language]
- **Location**: producer `inject.js:2097-2104` (`post('pagevitals', {why, vitals})` is the only such post in the repo); call sites `inject.js:2173` (`'arm'`), `2260` (`'giveup'`), `2283` (`'cancel'`) — all inside §5c — plus `1924` (`'giveup'`, the sibling dead-ended `reloadForMissingConversation`). Consumer: `ShellRuntime.kt:1540-1547` (`"pagevitals" -> … Diagnostics.log("debug", "页面体征(…)")`).
- **Symbol**: `postPageVitals`, the `pagevitals` protocol frame
- **Why dead**: every producer is on the watchdog's terminal ladder, and the frame's own comment in Kotlin says so ("卡死看门狗布防/撤防/放弃时的现场"). If the ladder goes, `postPageVitals` has zero callers *and* the Kotlin branch has zero senders — dead on both sides of the bridge.
- **Reference search**: `pagevitals` over `android-shell/` → 6 hits: `inject.js:2101` (the only `post`), 1924/2173/2260/2283 (calls), `ShellRuntime.kt:1540` (only handler). `postPageVitals` → 2101(def)+2173+2260+2283+1924, no others. No test asserts on the frame.
- **Confidence**: high
- **Suggested action**: delete with the watchdog block; **also delete `ShellRuntime.kt:1540-1547`** (Kotlin side is outside my range — flagging for the Kotlin agent)
- **Risk if removed**: loses the `pagevitals` diagnostic line in `zcode-shell.log`; no functional path depends on it.

### F8. Debug-surface keys `arm` / `cancel` / `fire` / `vitals` of `__zcodeShellFallback` have zero references [NO-REFS]; the whole surface is test-only [TEST-ONLY] — *boundary finding, lines are in the adjacent range*
- **Location**: `inject.js:1986-1992` (`arm: stallArm, cancel: stallCancel, vitals: readVitals, fire: stallFire, stall: function () { return stallState; }`); object opened at `inject.js:1974`
- **Symbol**: `G.__zcodeShellFallback` keys `arm`, `cancel`, `fire`, `vitals`
- **Why dead**: this object is the *only* test/debug surface for the §5b/§5c cluster. Tests use `note`, `check`, `ready`, `state`, `timer`, `stall` — never `arm`/`cancel`/`fire`/`vitals` (so those four keys are unreferenced by production **and** by tests). Kotlin never evaluates `__zcodeShellFallback` at all, so the surface exists purely for `inject.test.js`.
- **Reference search**: `__zcodeShell\w+` over `android-shell/` → 101 hits; `__zcodeShellFallback` appears only in `inject.js:1974` and `tools/inject.test.js:414, 1287`. `\.(arm|cancel|fire|vitals|stall|beacon|readyReasons)\b` over `tools/inject.test.js` → the only surface hits are `FB().stall()` (1410, 1456, 1465); `__zcodeShellFallback|stallState` over `app/src/main/java` → **No matches**.
- **Confidence**: high
- **Suggested action**: investigate further / hand to the agent owning 1–1994 — the four unreferenced keys are safe to drop with the module; the rest of the surface is a deliberate test harness (keep, or delete together with the §5b ladder)
- **Risk if removed**: `arm/cancel/fire/vitals` — none. Deleting `stall()` breaks `inject.test.js:1410, 1456, 1465`.

### F9. The 1s patrol keeps re-arming a ladder whose terminals all refuse  [VESTIGIAL]
- **Location**: `setInterval(conversationStreamStallTick, 1000)` at `inject.js:2313` (started unconditionally at bootstrap, `inject.js:3730`); patrol body `2287-2304`; ladder `stallArm` (2143) → 3s → `stallFire` (2176) → 3s → `stallReloadIfAllowed` (2252-2261)
- **Symbol**: `conversationStreamStallTick`, `startConversationStreamMonitor`, `G.__zcodeShellConversationMonitor`
- **Why dead**: the patrol still does real reads (`client.lastPageConversationTrafficAt()` vs `CONVERSATION_STALE_MS = 60000` at `inject.js:1682`) and really arms — but the ladder's two terminals are now: (a) `nudgeReconnect` → one throttled `noteReadOnlyRefusal` line, `return false`; (b) `stallReloadIfAllowed` → `gaveUp = true; armed = false;` + 2 log lines + 1 `pagevitals` post. Net effect of a full arm→fire→fire cycle (≈6s): **3 diag lines, 1 `pagevitals` frame, and a `gaveUp` latch that only suppresses further log lines** (2144, 2289). It cannot change page state, cannot recover anything, and the `STALL_REARM_COOLDOWN_MS = 60000` back-off (2028, read at 2298) exists purely to limit that log volume — i.e. the module's documented *purpose* is now log-throttling.
- **Reference search**: `conversationStreamStallTick|startConversationStreamMonitor` → defined 2287/2306, called only at 2313 / 3730. `G.__zcodeShellConversationMonitor` → 2312 (guard) + 2313 (assign), never cleared, never read elsewhere. `stallArm|stallCancel|stallFire|stallReloadIfAllowed|nudgeReconnect` call-graph inspected exhaustively: arms at 1432, 1434, 2299, 2320; cancels at 1437, 1888, 2185, 2193, 2214, 2326; fire only from 2168, 2222; `stallReloadIfAllowed` only from 2225; `nudgeReconnect` from 1906, 2218, 2933.
- **Confidence**: high
- **Suggested action**: delete the module (see "Removal accounting")
- **Risk if removed**: see below.

---

## Removal accounting (the deferred block, quantified)

**Removable inside my range: ~290 lines** — `inject.js:1995-2062` (68: section header comment, `STALL_*`, `PAGE_LOG_*` tables, `stallState`) + `2106-2141` (36: zombie constants/`zombieSilenceS`/`zombieSuspected`) + `2143-2328` (186: `stallArm`, `stallFire`, `nudgeReconnect`, `stallReloadIfAllowed`, `stallCancel`, `conversationStreamStallTick`, `startConversationStreamMonitor`, `notePageLogEvent`).

**Explicitly NOT part of the removal unit** (keep — they have live consumers):
| kept | lines | live consumer |
| --- | --- | --- |
| `readVitals`, `vitalsStalled`, `postPageVitals` | 2064-2104 | heartbeat patrol (`1430-1431`), `vitals` diag (`2928`), `conversationReadyReasons` (`1844`), `FB().vitals` |
| `STALL_RELOAD_MS` | 2016 | `fallbackCheck` (`1907`) — §5b, not §5c |
| `PAGE_LOG_RECOVER_EVENTS` + the `else if` half of `notePageLogEvent` | 2035-2039, 2324-2327 | `noteConversationReady` (`1822`), which is the first-hand ready signal asserted by `inject.test.js:1374` |
| `zombieSilenceS` read in the patrol's log string | 1434 | diagnostic text |

**Companion edits outside my range (~30 lines) — required for the module to actually go:**
1. `inject.js:1986-1992` — drop the `arm`/`cancel`/`fire`/`vitals`/`stall` surface keys (F8).
2. `inject.js:1429-1441` — the heartbeat patrol's `try` block is *only* stall plumbing (`stallArm`/`stallCancel`); it collapses, but keep the `readVitals()` call if the `zombieSilenceS` diagnostic log line is wanted.
3. `inject.js:1888` (`stallCancel('进对话 5s 判定…')`), `1906` (`nudgeReconnect('进对话 5s 未出详情')`), `1922` (`stallState.gaveUp = true`) — the §5b `fallbackCheck` ladder reaches into §5c for both its terminal *and* its state; these three lines must be rewritten (its own second-deadline branch at 1904-1911 then becomes a pure duplicate log).
4. `inject.js:2931-2934` — the `l1_test` diag branch calls `nudgeReconnect`; it must be re-pointed at `noteReadOnlyRefusal` directly or dropped, **and `README.md:1011` updated** ("`l1_test` … 轻推页面 socket（只读壳下只记一行）").
5. `inject.js:3730` — delete `startConversationStreamMonitor();`.
6. `ShellRuntime.kt:1540-1547` — delete the `pagevitals` branch (F7).
7. `tools/inject.test.js` — 3 touch points: `1410` and `1465` (assert `FB().stall().gaveUp`), `1456` (writes `lastReloadAt`). The block comment at `1277-1285` also states the current contract ("判定照做、状态照记（`__zcodeShellFallback` 的 stall/ready 记账）"). Tests `1390-1415`, `1417-1437`, `1439-1470` would need rewriting; the rest of the §5b suite (`1324-1389`, `1472-1539`) does not touch `stall()` and would survive.

**Total realistic deletion: ~290 lines in this range + ~30 companion lines elsewhere.**

---

## Known / intentional (not a new finding)

- **§5c watchdog block as a whole — deferred on purpose.** `docs/18` §5 row 2: "四个'终点'函数体已删，但模块壳还在跑（每 5s 布防/巡检，终点全被守卫拒）… 整块删要重核 `fallbackCheck` / `stallArm` / `stallFire` / DOM 信标 / `sessionStorage` 键的交叉引用". **Re-check: reason still holds — yes, and it is now precisely enumerated above** (the cross-references are exactly items 1-5 of the removal accounting; the `sessionStorage` key the doc worried about is F2, proven dead). The doc's "每 5s" figure is slightly off: the patrol is **1s** (`2313`), and a full arm→give-up ladder is 3s+3s (`STALL_NUDGE_MS`/`STALL_RELOAD_MS`).
- **`nudgeReconnect` (2244) and `stallReloadIfAllowed` (2252) as guard+log stubs** — `docs/18` §2.3 records deleting their implementations. Confirmed still stubs (`2248-2249`, `2257-2260`). Not re-reported.
- **`nudgePageRecovery` + `nudge_now` / `nudge_close` / `bg_redial:close`** — `docs/18` §4: kept as pure forensic diagnostics; README does not document them as production. **Reason still holds** — and note this is the *only* remaining path that can close the page's socket (`closeRelaySocketForNudge`, `inject.js:2514-2526`, called at 2547/2556), i.e. the one surviving hole in the `SHELL_READ_ONLY` contract, reachable only by explicit adb command (test-pinned at `inject.test.js:1728`).
- **`kick_test` / `degrade_test` (§5d/§5e)** — `docs/18` §4 keeps `carrier_*` experiment hooks; these two are the same class (adb-driven experiment points, README:1012) and are still wired end-to-end (verified above). Not dead; no test covers them, so any removal needs a README edit but no test edit.
- **`deadlink_test`** — `inject.test.js:634` asserts it returns `true`; the rest of that path (`__zcodeShellSetAppForeground` → `healDeadLinkOnResume`, `inject.js:1255-1289`) is live production code. Out of my range, verified only as a caller.

## Suspected but unproven

- **The §5b `fallbackCheck` cluster** (`inject.js:1672-1993`: `fallbackState.beaconAt/readyAt/readyBy/nudgedAt`, `scheduleFallbackCheck`, `fallbackCheck`, `noteConversationReady`, `conversationReadyReasons`, `reloadForMissingConversation`) — `reloadForMissingConversation` (1918-1925) is a stub (`stallState.gaveUp = true` + log) and `fallbackState.nudgedAt` (1694/1814/1904-1905) now gates nothing but a second dead-end log. **Missing evidence**: the whole cluster is in another agent's range (1-1994); I only confirmed the three §5c cross-references and that `inject.test.js:1324-1539` pins its behaviour. Do not delete on my evidence alone. Note the task premise "`stallState.nudgedAt`" does **not** exist — the field is `fallbackState.nudgedAt` (`inject.js:1694`).
- **`G.__zcodeShellConversationMonitor`** (`inject.js:2312-2313`) — never cleared and never read programmatically (only as an idempotency guard). Suspected only because the guard is its sole purpose; not dead while the monitor exists. **Missing evidence**: none needed if F9 is accepted.

## Summary

- **Findings: 9** — NO-REFS 3 (F1, F3, F8), WRITE-ONLY 2 (F2, F4), UNREACHABLE 1 (F5), VESTIGIAL 3 (F6, F7, F9), TEST-ONLY 1 (F8, double-tagged: the `__zcodeShellFallback` surface). F8's lines are in the adjacent range (flagged as a boundary finding).
- **Highest-value deletions**:
  1. `inject.js:2208-2216` — the rate-limit branch whose clock (`lastForcedReconnectAt`) has had **no writer since `forceReconnect` was stubbed** (`inject.js:1156`, `1494-1497`).
  2. `inject.js:2029 + 2055-2061 + 2280-2281` — `STALL_STORE_RELOADS`/`reloadCount`: a sessionStorage round-trip that is a no-op in both directions.
  3. `inject.js:2048, 2050, 2017` — `lastReloadAt` (written only by `inject.test.js:1456`), `streamStalled`, `STALL_RELOAD_CAP`: three watchdog fields/constants with zero readers.
- **Estimated removable lines**: **~290 in range (1995-2328)**, ~30 companion lines outside it (~320 total) — conditional on the 7 companion edits listed above.

## Status verification (2026-09-19)

Verified against the live tree: `inject.js` is now **3721 lines**. The §5c decision machine named in
the Removal accounting was deleted in **batch 4b (inside b627610)**; the surviving "量具" match the
prediction. Original text above is unchanged.

| 条目 | 原判定 | 当前状态 | 备注 |
| --- | --- | --- | --- |
| F1 `STALL_RELOAD_CAP` | NO-REFS | 已删除 | §5c 决策机拆除（批次4b，b627610） |
| F2 `stallState.reloadCount` + `STALL_STORE_RELOADS` | WRITE-ONLY | 已删除 | 同上 |
| F3 `stallState.streamStalled` | NO-REFS | 已删除 | 同上 |
| F4 `stallState.lastReloadAt` | WRITE-ONLY | 已删除 | 同上 |
| F5 180s 限速死支 + `lastForcedReconnectAt` + `RECONNECT_MIN_GAP_MS` | UNREACHABLE | 已删除 | `stallFire` 连同决策机整体删除（批次4b） |
| F6 `stallState.armReason` | VESTIGIAL | 已删除 | 同上 |
| F7 `postPageVitals` + `pagevitals` 通道 | VESTIGIAL | 仍在（活着，判定反转） | 决策机删除后心跳巡检改为边沿驱动直接上报 `why='stalled'/'periodic'`（`:1424`），§5b giveup 仍走 `:1994`；Kotlin 分支保留并改注（`ShellRuntime.kt:1961-1966`：why 只剩三种）——"随梯子一起死"的预测未发生 |
| F8 `__zcodeShellFallback` 的 `arm`/`cancel`/`fire`/`vitals` | NO-REFS / TEST-ONLY | 部分已删 | `arm`/`cancel`/`fire` 已删（批次4b）；`vitals: readVitals` 仍在（`:2061`）且仍零读者 |
| F9 1s 巡检 re-arming 死梯 | VESTIGIAL | 已删除 | `conversationStreamStallTick` + `startConversationStreamMonitor` 整体删除（批次4b）；`__zcodeShellConversationMonitor` 一并消失 |
| Removal accounting：§5c 整块 | 延期处理 | 已执行 | 机器删净（批次4b），留下的量具与预测一致：`readVitals :2105`、`vitalsStalled :2138`、`postPageVitals :2243`、`nudgeReconnect :2276`、`notePageLogEvent :2293`；配套项 #6（Kotlin pagevitals 分支）按新理由保留未删；#4 的 `l1_test` 仍走 `nudgeReconnect`（`:2908-2910`，即新的记账出口） |
| known: `nudgeReconnect` / `stallReloadIfAllowed` stub | 有意保留 | 一留一删 | `nudgeReconnect` stub 仍在（`:2276`）；`stallReloadIfAllowed` 已随 4b 删除 |
| known: `kick_test` / `degrade_test` | 实验件保留 | 仍在（活着） | `relayKickTest :2313` / `relayDegradeTest :2395`，diag 分发 `:2912-2916` |
| known: `deadlink_test` | 测试钉住 | 仍在（活着） | `:2918` 分支；`inject.test.js:634` 仍断言返回 `true` |
| known: `nudge_now`/`nudge_close` → `__zcodeShellNudgeRecover` | 取证保留 | 仍在（活着） | `ShellRuntime.kt:1495` |
| suspected: §5b `fallbackCheck` 簇 | 未证实（他段） | 仍在（活着） | §5b 整体保留（`fallbackState :1752`、`fallbackCheck :1930`）；`nudgedAt` 现状见 inject-js-b 的 F9 |
| suspected: `__zcodeShellConversationMonitor` | 未证实 | 已删除（随批次4b） | 监视器不存在，守卫随之消失 |

小结：9 条 finding —— 已删 7（F1–F6、F9，全部随批次4b），仍活 1（F7 判定反转），部分已删 1
（F8）。最值得注意：本文件末尾的 Removal accounting 几乎被逐字执行（机器删净、量具按清单保留）；
唯一反例是 F7——`pagevitals` 通道没有随梯子死亡，心跳改为边沿上报后它反而成了活通道。
