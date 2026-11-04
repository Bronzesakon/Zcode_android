> ⚠️ Historical audit baseline (2026-09-18, pre-cleanup). Status as of 2026-09-19: see "Status verification" section at the end. Many items listed as dead have since been deleted in batches 1–4 (commit b627610).

# inject-js-b — dead-code findings

## Scope audited
- `android-shell/app/src/main/assets/inject.js:1106-1994` — page-injected layer: §4 heartbeat /
  staleness recovery / resume posture, §5 notification-tap task locator, §5b "conversation did not
  appear within 5 s" beacon (`fallbackCheck` / `fallbackState`).
- Cross-checked trees (reads + greps): `android-shell/app/src/main/assets/zcode-protocol.js`,
  `android-shell/app/src/main/java/com/zcode/remote/**` (`MainActivity.kt`, `ShellRuntime.kt`,
  `WebAppBridge.kt`), `android-shell/tools/*` , `android-shell/README.md`, and the whole
  `E:\Zcode_harmony` tree for the workspace-wide checks.
- Test baseline: `cd android-shell; node --test tools/inject.test.js` → **45 pass / 0 fail**.

Live-by-evidence (NOT findings, listed so the negative is explicit): `healDeadLinkOnResume` is called
at `inject.js:3002`; `noteRelayKicked` at `:1054`; `healKickedOnForeground` at `:2982`;
`kickedHealCount`/`KICKED_HEAL_STORE`/`KICKED_HEAL_CAP`/`storeGet`/`storeSet` via `:1022-1027,1302-1325`;
`noteReadOnlyRefusal` via `:1495,2248`; `forceReconnect` via `:1464,3006`; `reportLiveness` via
`:1442,3113,3734`; `conversationReadyReasons`/`noteConversationReady` via `:1883,2325`;
`scheduleFallbackCheck` via `:1946,3259`; `CONVERSATION_STALE_MS` via `:2294`; `clientGenSeq` via `:811`;
`notePageRpcCall`/`notePageRpcResult`/`UPLOAD_RPC_RE` via `:827,834`; `findHeaderText`/`composerElement`/
`elementDisabled`/`fallbackElementVisible` via `:1893-1896` and `:2071,2081-2082`; `CLICKABLE`/`isClickable`
via `:1523,1587`; `clickElement` via `:1612,2872`; `fallbackState.chatView` via `:2289,3244-3247`.

## Findings

### F1. `startHeartbeat()` and `HEARTBEAT_MS` have no caller — the in-page interval was replaced by the native pump  [NO-REFS]
- **Location**: `app/src/main/assets/inject.js:1391-1395` (function), `inject.js:1129` (`HEARTBEAT_MS`)
- **Symbol**: `startHeartbeat`, `HEARTBEAT_MS`
- **Why dead**: the only thing that used to arm the 10 s interval was `startHeartbeat()`. Heartbeat
  ticks are now driven from Kotlin (`ShellRuntime.startHeartbeatPump()` →
  `evaluateJs("window.__zcodeShellHeartbeat && window.__zcodeShellHeartbeat();")`, `ShellRuntime.kt:548`),
  so the in-page timer helper is never invoked and `HEARTBEAT_MS` exists solely to feed it.
- **Reference search**: `\bstartHeartbeat\b` over `E:\Zcode_harmony` → 7 hits: `inject.js:1391` (decl),
  `inject.js:1393` (its own body), and `ShellRuntime.kt:262,562,1316` + `:562` which are the unrelated
  Kotlin member `startHeartbeatPump` (different identifier). No `G.__zcodeShellStartHeartbeat` exists.
  `HEARTBEAT_MS` over `E:\Zcode_harmony` → 2 hits, `inject.js:1129` (decl) and `inject.js:1394` (inside
  the dead function).
- **Confidence**: high
- **Suggested action**: delete both (≈6 lines incl. the doc comment at `:1391`).
- **Risk if removed**: none. `__zcodeShellHeartbeat` (`:1503`) is the live entry point and stays; no test
  references `startHeartbeat`/`HEARTBEAT_MS` (`node --test tools/inject.test.js` passes 45/45 today and
  greps show zero test hits).

### F2. `liveness.startedAt` is initialised and never read  [WRITE-ONLY]
- **Location**: `app/src/main/assets/inject.js:1353`
- **Symbol**: `liveness.startedAt`
- **Why dead**: process-start timestamp of the liveness counter block. It is neither read anywhere in
  `inject.js` nor included in the `post('liveness', …)` payload, so the value is unreachable.
- **Reference search**: `startedAt` over `android-shell` → `inject.js:1353` (this field) plus unrelated
  locals (`:480,483,494,581,582` in `recordDecode`) and unrelated Kotlin (`ShellRuntime.kt:246`,
  `NativeRelayClient.kt:108`). `liveness\.startedAt` over `E:\Zcode_harmony` → 0 hits.
- **Confidence**: high
- **Suggested action**: delete the field (1 line).
- **Risk if removed**: none; not asserted by any test.

### F3. `liveness.outboundFrames` counter is maintained and reported but nothing consumes it  [NO-REFS]
- **Location**: `app/src/main/assets/inject.js:940` (increment), `:1340` (init), `:1376` (payload field)
- **Symbol**: `liveness.outboundFrames`
- **Why dead**: the counter is incremented per outbound frame and copied into the `liveness` beacon, but
  the Kotlin consumer `ShellRuntime.onLiveness()` (`ShellRuntime.kt:158-172`) parses only
  `inboundFrames, pairAcks, socketsOpened, socketsClosed, backgroundTicks, backgroundFirstTickDelayMs,
  lastInboundAgoMs, paired, socketState, convFrameAgoMs` — `outboundFrames` is not in the `Liveness`
  data class (`:107-136`) and never surfaces in `livenessSummary()` (`:1435-1436`).
- **Reference search**: `outboundFrames` over `E:\Zcode_harmony` → 5 hits, all in `inject.js:940,1340,1376`
  (these three) and the two `inboundFrames` neighbours — zero hits in any `.kt`, `tools/*`, or `*.md`.
- **Confidence**: high
- **Suggested action**: delete the counter, its init and the payload field (3 lines).
- **Risk if removed**: none for code; it is only a human-readable diagnostic that is currently thrown
  away before any human can see it. If the intent is to keep it as instrumentation, it must be added to
  `ShellRuntime.Liveness` first.

### F4. `injectedReady: true` beacon field is hardcoded and has no reader  [NO-REFS]
- **Location**: `app/src/main/assets/inject.js:1387`
- **Symbol**: `injectedReady` (payload field of the `liveness` post)
- **Why dead**: the literal is always `true` and no consumer reads it. Kotlin's own `injectedReady`
  flag is a **different** value: it is set by the `"ready"` bridge event (`ShellRuntime.kt:1579-1582`,
  gated by `onPageReloaded()`), never by the liveness payload.
- **Reference search**: `injectedReady` over `E:\Zcode_harmony` → `inject.js:1387` (this literal),
  `ShellRuntime.kt:88,1582,1860,1871` (the unrelated private flag). No test and no tool reads the payload
  field. For contrast, its sibling `deviceSidKnown` (`:1386`) *is* read by `tools/inject.test.js:918`.
- **Confidence**: high
- **Suggested action**: delete the field (1 line). Do not delete Kotlin's `injectedReady`.
- **Risk if removed**: none — nothing asserts it.

### F5. Two `lastReloadAt` fields are written by nothing / read by nothing  [WRITE-ONLY]
- **Location**: `app/src/main/assets/inject.js:1688` (`fallbackState.lastReloadAt`),
  `app/src/main/assets/inject.js:2048` (`stallState.lastReloadAt`)
- **Symbol**: `fallbackState.lastReloadAt`, `stallState.lastReloadAt`
- **Why dead**: both were the "when did we last reload" stamp for the deleted reload limiter.
  `fallbackState.lastReloadAt` is never read **and never assigned** after the object literal;
  `stallState.lastReloadAt` is never read in production — its only writer is the test
  (`tools/inject.test.js:1456` sets it to fake a 16 s-old reload, and the assertions that follow expect
  **no** reload anyway).
- **Reference search**: `lastReloadAt` over `android-shell` → 3 hits: `inject.js:1688` (decl),
  `inject.js:2048` (decl), `tools/inject.test.js:1456` (write). Zero reads.
- **Confidence**: high (for `fallbackState.lastReloadAt`), high (for `stallState.lastReloadAt` — the test
  write has no observable effect: `FB().check()` at `:1458` asserts `reloads.length === 0`).
- **Suggested action**: delete both fields; drop the now-meaningless `FB().stall().lastReloadAt = …` line
  in the test (the assertion it precedes still holds).
- **Risk if removed**: test line `inject.test.js:1456` must be edited (it writes a field that no longer
  exists — writing to a missing property on the state object would silently pass, so the test would keep
  passing, but the line should go).

### F6. `FALLBACK_RELOAD_GAP_MS` / `FALLBACK_STORE_AT` are orphaned constants of the deleted sessionStorage limiter  [NO-REFS]
- **Location**: `app/src/main/assets/inject.js:1683`, `app/src/main/assets/inject.js:1684`
- **Symbol**: `FALLBACK_RELOAD_GAP_MS` (`15000`), `FALLBACK_STORE_AT` (`'zcodeShellFastRefreshAt'`)
- **Why dead**: the §5b header comment (`:1652`) still documents "15 s 最小刷新间隔（跨 reload 由
  sessionStorage 记）", but that implementation was deleted in the 2026-09-16 audit — see the tombstone
  at `:1921` ("历史实现（两级刷新 + sessionStorage 限流 + 连续上限）已删除"). Nothing reads either
  constant and no `sessionStorage` key `zcodeShellFastRefreshAt` is ever written, so this is a
  write-never/read-never store key.
- **Reference search**: `FALLBACK_RELOAD_GAP_MS` over `android-shell` → 1 hit (`inject.js:1683`, decl).
  `FALLBACK_STORE_AT` over `android-shell` → 1 hit (`inject.js:1684`, decl).
  `storeGet|storeSet` → only `:1027,1224,1315,1782,1790,2281`, none with `FALLBACK_STORE_AT`.
- **Confidence**: high
- **Suggested action**: delete both (2 lines) and fix the stale sentence in the `:1650-1655` header
  comment (comment-only change, no production lines).
- **Risk if removed**: none; no test references either name.

### F7. `lastForcedReconnectAt` is never assigned, so the 180 s throttle branch is permanently false  [WRITE-ONLY] (+ `UNREACHABLE` branch, `VESTIGIAL` constant)
- **Location**: `app/src/main/assets/inject.js:1156` (decl, my range), `inject.js:1132`
  (`RECONNECT_MIN_GAP_MS`, my range); the branch it makes dead is `inject.js:2208-2216`
  (outside my range — flagged here because the cause is in my range)
- **Symbol**: `lastForcedReconnectAt`, `RECONNECT_MIN_GAP_MS`
- **Why dead**: the old `forceReconnect` body wrote this stamp (the comment at `:2210` still claims
  "它只写 `lastForcedReconnectAt`"), but the surviving stub (`:1494-1497`) only logs. The variable is
  therefore pinned at `0`, and `Date.now() - 0 <= 180000` is false for any real clock, so the
  `else if` at `:2208` can never be taken and its `stallCancel(...)` at `:2214` is unreachable.
  `RECONNECT_MIN_GAP_MS` now survives only inside that permanently-false comparison and its log text.
- **Reference search**: `lastForcedReconnectAt` over `E:\Zcode_harmony` → 3 hits: `inject.js:1156`
  (decl), `:2208` (read), `:2210` (comment). Zero assignments anywhere — a repository-wide grep for the
  identifier cannot miss an assignment. `RECONNECT_MIN_GAP_MS` over `android-shell` → `:1132` (decl),
  `:2208`, `:2215` (log string).
- **Confidence**: high (never assigned); high (comparison is false for any wall clock after 1970-01-03).
- **Suggested action**: delete `lastForcedReconnectAt` and the dead `else if` branch (the `else` at
  `:2217` becomes unconditional); delete `RECONNECT_MIN_GAP_MS` with it. Coordinate with the owner of
  lines 1995+ so the branch deletion happens once.
- **Risk if removed**: none functionally — the throttle it "implements" is already inert; the comment at
  `:2209-2213` documents a historical bug and would become stale.

### F8. `STALL_STORE_RELOADS` + `stallState.reloadCount`: a counter that is loaded and reset but never incremented or read  [VESTIGIAL] [WRITE-ONLY]
- **Location**: `app/src/main/assets/inject.js:2029` (`STALL_STORE_RELOADS`),
  `:2055-2057` (`reloadCount` init from sessionStorage), `:2280-2281` (reset to `0` + storeSet)
  — all outside my nominal range, reported because my brief names them as new dead matter left by the
  four deleted terminal functions
- **Symbol**: `STALL_STORE_RELOADS` (`'zcodeShellStallReloads'`), `stallState.reloadCount`
- **Why dead**: the field fed the deleted "consecutive reload cap". Today it is read exactly once — to
  hydrate itself from `sessionStorage` — and written exactly once, back to `0`. It is never
  incremented, never compared, never displayed, so the store key is a pure round-trip `0`.
- **Reference search**: `STALL_STORE_RELOADS` over `android-shell` → `inject.js:2029,2057,2281`.
  `reloadCount` over `android-shell` → `inject.js:2055,2280` (init, reset) and no third hit — no test,
  no Kotlin, no tool, no read.
- **Confidence**: high
- **Suggested action**: delete the constant, the field and the `storeSet` reset (~5 lines).
- **Risk if removed**: none in production. Note `tools/inject.test.js` does not reference
  `reloadCount`/`STALL_STORE_RELOADS` (grep: 0 hits), so tests are unaffected.

### F9. `fallbackState.nudgedAt` + the second half of `fallbackCheck` survive only as a two-step log dance  [VESTIGIAL]
- **Location**: `app/src/main/assets/inject.js:1694` (field), `:1814` (reset in
  `scheduleFallbackCheck`), `:1904-1911` (branch that uses it)
- **Symbol**: `fallbackState.nudgedAt`
- **Why dead-ish**: `nudgedAt` exists solely to split the §5b deadline into "nudge first, reload on the
  next pass". Both terminal actions are now log-only stubs:
  `nudgeReconnect` (`:2244-2250`, `noteReadOnlyRefusal(...)` + `return false`) and
  `reloadForMissingConversation` (`:1918-1925`, log + `postPageVitals('giveup')`). So the field's only
  remaining job is to choose between two inert branches and to schedule a second `fallbackCheck` 3 s
  later (`:1907`, `STALL_RELOAD_MS`). The one real side effect left on that path is the `stallState.gaveUp`
  latch set at `:1922`, which permanently silences the stall watchdog for the rest of the page load
  (`stallArm` returns early at `:2144`).
- **Reference search**: `nudgedAt` over `android-shell` → `inject.js:1694,1814,1905` (write) and `:1904`
  (read). No other reader; no test reads it. `nudgeReconnect`/`reloadForMissingConversation` call sites:
  `:1906,2218,2933` / `:1911` only.
- **Confidence**: high that the state is vestigial; **the two stubs themselves are the documented
  deferral** (see "Known / intentional"), so this is reported only as the residue.
- **Suggested action**: keep but annotate now, or delete `nudgedAt` + the second-pass re-arm and collapse
  `fallbackCheck` to a single "log + latch `gaveUp`" step.
- **Risk if removed**: `tools/inject.test.js:1390-1415` and `:1439-1470` pin this exact two-pass
  behaviour — they assert the `先尝试最小内推` and `只读壳：不因` diag lines and
  `FB().stall().gaveUp === true` after `clearTimeout(FB().timer()); FB().check()`. Any collapse must
  update those two tests first.

### F10. Six members of the `__zcodeShellFallback` debug surface have zero readers  [NO-REFS]
- **Location**: `app/src/main/assets/inject.js:1976` (`beacon`), `:1979` (`readyReasons`),
  `:1986` (`arm`), `:1987` (`cancel`), `:1988` (`vitals`), `:1989` (`fire`)
- **Symbol**: `G.__zcodeShellFallback.{beacon,readyReasons,arm,cancel,vitals,fire}`
- **Why dead**: the object is reached only from the test harness (`tools/inject.test.js:1287`
  `const FB = () => globalThis.__zcodeShellFallback;`), and that harness uses only
  `note`, `check`, `ready`, `state`, `timer`, `stall` (grep of `FB().xxx` below). The other six are
  aliases of internal functions that are already reachable internally, so nothing can call them.
- **Reference search**: `__zcodeShellFallback` over `E:\Zcode_harmony` → 4 hits: `inject.js:1974`
  (definition), `tools/inject.test.js:414` (cleanup `delete`), `:1283` (comment), `:1287` (the `FB`
  alias). `__zcodeShellFallback\.` → 0 hits. `FB\(\)\.(beacon|readyReasons|arm|cancel|vitals|fire)` →
  0 hits; `FB\(\)\.` enumerates only `note, check, ready, state, timer, stall`. No Kotlin
  `evaluateJavascript` string mentions the object (`grep __zcodeShell` in `app/src/main/java` → only
  `__zcodeShellHeartbeat`, `__zcodeShellLocateTask`).
- **Confidence**: high for `readyReasons`/`beacon` (pure aliases, also exportable via `FB().state()`);
  medium for `arm`/`cancel`/`vitals`/`fire` — the in-file comment calls this a "Test and console
  debugging surface", so a human may call them from `chrome://inspect` ad hoc. No *code* reader exists.
- **Suggested action**: delete `beacon` and `readyReasons` (2 lines); keep `arm`/`cancel`/`vitals`/`fire`
  but annotate as a manual console surface, or delete them too if manual poking is not a supported
  workflow.
- **Risk if removed**: none for tests/Kotlin. Loss is limited to manual console debugging.

### F11. `__zcodeShellLocateTask(sessionId, title)` never reads its `sessionId` parameter  [NO-REFS]
- **Location**: `app/src/main/assets/inject.js:1601-1632` (parameter at `:1601`)
- **Symbol**: `sessionId` (parameter of `G.__zcodeShellLocateTask`)
- **Why dead**: the body matches purely on `title` (`findTaskElement(title)` at `:1610`); the session id
  is accepted and dropped. It is not a naming mismatch — Kotlin really passes `(sessionId, title)` in
  that order (`MainActivity.kt:701-704` builds `pendingLocate = sessionId to title`, `:731-736` emits
  `__zcodeShellLocateTask(<sessionId>, <title>)`), so the signature is correct and the first argument is
  simply unused.
- **Reference search**: `__zcodeShellLocateTask` over `android-shell` → `inject.js:11` (file header
  comment). `:1601` (decl), `MainActivity.kt:732` (emitter), `tools/inject.test.js:409,856,874,1121`
  (always called with 2 args). Inside `:1601-1632` the identifier `sessionId` appears only in the
  parameter list.
- **Confidence**: high
- **Suggested action**: keep but annotate the parameter as reserved (the notification extras still carry
  it) — or drop it and update the Kotlin emitter; the title-only matcher is documented as the D11 design
  (`:1510-1513`), so annotating is the lower-risk option.
- **Risk if removed**: changing arity requires touching `MainActivity.kt:731-736`; a JS-side drop alone
  would silently ignore the first argument (harmless).

## Known / intentional (not a new finding)
- **The four terminal functions are stubs by design** — `forceReconnect` (`inject.js:1494-1497`),
  `nudgeReconnect` (`:2244-2250`), `stallReloadIfAllowed` (`:2252+`), `reloadForMissingConversation`
  (`:1918-1925`) were gutted to "guard + one log line" per `docs/18-全仓审计-错误路线残留与清理.md`
  §2.3/§5 and `README.md:454` (read-only shell, five permitted page writes). Re-check: **reason still
  holds** — the policy constant `SHELL_READ_ONLY = true` (`:1187`) is unchanged, and
  `tools/inject.test.js:1390-1470` now actively asserts the stubbed behaviour ("只读壳：不轻推页面
  socket", "只读壳：不因", `reloads.length === 0`). Do not delete the stubs without a product decision.
- **`healDeadLinkOnResume` + `G.location.reload()` (`:1243-1290`) and `healKickedOnForeground`
  (`:1302-1325`)** — the two page reloads that survive inside the read-only shell. `README.md:454`
  lists them as permitted writes ③ and ⑤, and `:2995-3004` shows the live call site. Reason still holds:
  yes; both target links already declared dead (KICKED terminal state / zero inbound frames in the
  observation window). Note their `RESUME_HEAL_*` / `KICKED_HEAL_*` state is fully live.
- **`SHELL_READ_ONLY` (`:1187`) itself** — long-term capability constant, not dead code. Everything it
  seals in my range (`:2995-3004` branch, the `!appForeground` guard at `:1478`) is deliberate.

## Suspected but unproven
- `liveness.backgroundTicks` / `backgroundFirstTickDelayMs` — proven consumed by Kotlin
  (`ShellRuntime.kt:166-167,186-187,266-267`) **only when `verdictPending`**; whether the milestone-4
  verdict path is still reachable was not part of my range. Not reported as a finding.
- `fallbackState.beaconGen` — read at `:1877`; I did not audit whether `client.gen` can ever differ
  across a beacon window (that is `zcode-protocol.js` territory). Left alone.
- `stallState.gaveUp` — genuinely read (`:1436,2144,2289`) and test-pinned (`inject.test.js:1410,1465`),
  so **not** dead; but its setter at `:1922` now latches it without any recovery action, which is a
  behaviour question (watchdog permanently disarmed after one missed conversation load), not dead code.

## Summary
- Findings: 11 (NO-REFS 5 — F1, F3, F4, F6, F10 (+F11 parameter); WRITE-ONLY 3 — F2, F5, F7;
  VESTIGIAL 3 — F7-constant, F8, F9). No `UNREACHABLE` declaration of my own; F7 makes an existing
  branch (`:2208`) unreachable from my range's state.
- Highest-value deletions:
  1. `inject.js:1391-1395` + `:1129` — `startHeartbeat()`/`HEARTBEAT_MS`, superseded by the Kotlin pump (`ShellRuntime.kt:548`).
  2. `inject.js:1156` + `:1132` — `lastForcedReconnectAt` never assigned ⇒ `RECONNECT_MIN_GAP_MS` throttles nothing and `:2208-2216` is dead.
  3. `inject.js:1683-1684` (+ `:1688`, `:2048`) — orphaned constants/fields of the deleted reload limiter (`FALLBACK_RELOAD_GAP_MS`, `FALLBACK_STORE_AT`, both `lastReloadAt`).
- Estimated removable lines: ~30 in my range (1106-1994), plus ~14 in the adjacent range
  (`:2029,2055-2057,2208-2216,2280-2281`) — coordinate with the 1995+ owner to avoid double deletion.
- Test impact: only F5 (test line `inject.test.js:1456`) and F9 (tests `:1390-1470`) require test edits;
  everything else is invisible to the suite (45/45 currently passing).

## Status verification (2026-09-19)

Verified against the live tree: `inject.js` is now **3721 lines** (HEAD `2c89b88` + uncommitted
档0观测 addition of `__zcodeShellViewSnapshot` / `__zcodeShellReportViewSnapshot`; 点卡片定位
`__zcodeShellPresetTask` added in `d08e256`). Original text above is unchanged.

| 条目 | 原判定 | 当前状态 | 备注 |
| --- | --- | --- | --- |
| F1 `startHeartbeat` + `HEARTBEAT_MS` | NO-REFS | 已删除 | b627610（同 A-F4）；§4 头部注释与 `:1150` 旁注一并订正 |
| F2 `liveness.startedAt` | WRITE-ONLY | 仍在（仍死） | 现行 `:1335` 初始化，全文件仍无读者 |
| F3 `liveness.outboundFrames` | NO-REFS | 仍在（仍死） | `:913` 递增 / `:1322` init / `:1358` 上报；Kotlin `Liveness` 解析（`ShellRuntime.kt:185-196`）仍不收它——新增收的是 `convFrameAgoMs`（`ShellRuntime.kt:160/:195`） |
| F4 `injectedReady: true` 信标字段 | NO-REFS | 仍在（仍死） | 现行 `:1369`；Kotlin 侧同名标志已迁 `core/InjectionReadiness.kt`；原文引用的 deviceSidKnown 测试对照（`inject.test.js:918`）也已不存在 |
| F5 两个 `lastReloadAt` | WRITE-ONLY | 已删除 | 两个字段均删（`stallState` 那份随批次4b）；测试写行已改为说明注释（`inject.test.js:1472`） |
| F6 `FALLBACK_RELOAD_GAP_MS` / `FALLBACK_STORE_AT` | NO-REFS | 已删除 | b627610 |
| F7 `lastForcedReconnectAt` / `RECONNECT_MIN_GAP_MS` / 180s 死支 | WRITE-ONLY + UNREACHABLE | 已删除 | 死支随 §5c 决策机删除（批次4b）；两个标识符全删，`RECONNECT_MIN_GAP_MS` 仅存于 `nudgeReconnect` 的历史注释（`:2272`） |
| F8 `STALL_STORE_RELOADS` + `stallState.reloadCount` | VESTIGIAL / WRITE-ONLY | 已删除 | 随 §5c 决策机（批次4b） |
| F9 `fallbackState.nudgedAt` 两段日志舞 | VESTIGIAL | 仍在（仍死） | `:1758` init / `:1878` 复位 / `:1970-1973` 读写；两个终点仍是只记不动：`nudgeReconnect`（`:2276`）、`reloadForMissingConversation`（`:1989`，log + `postPageVitals('giveup')`） |
| F10 `__zcodeShellFallback` 六个无读成员 | NO-REFS | 部分已删 | `arm`/`cancel`/`fire` 已删（批次4b）；`beacon`/`readyReasons`/`vitals` 仍在（`:2049-2062`）且仍无代码读者——测试只用 note/check/ready/state/timer（`stall` 键也随测试改写消失） |
| F11 `__zcodeShellLocateTask` 的 `sessionId` 形参 | NO-REFS | 仍在（仍死） | 签名已变为 `(sessionId, title, presetUsed)`（`:1590`），Kotlin 按 3 参下发（`MainActivity.kt:761`）；新参 `presetUsed` 活着（`d08e256` 点卡片进详情页），但 `sessionId` 在函数体内仍一次未读 |
| known: 四个终点 stub | 按设计保留 | 仍在（活着，按设计） | `forceReconnect :1483`、`nudgeReconnect :2276`、`reloadForMissingConversation :1989` 仍为守卫+日志；`stallReloadIfAllowed` 已随 §5c 删除 |
| known: 两个自愈重载 | 有意保留 | 仍在（活着） | `healDeadLinkOnResume :1225`（读者 `:2979`）、`healKickedOnForeground :1284`（读者 `:2959`） |
| known: `SHELL_READ_ONLY` | 能力常量 | 仍在（活着） | 现行 `:1169` |
| suspected: `backgroundTicks` 判定可达性 | 未证实 | 仍在（活着） | Kotlin 仍解析该字段（`ShellRuntime.kt:189`） |
| suspected: `fallbackState.beaconGen` | 未证实 | 仍在（未变） | `:1754` |
| suspected: `stallState.gaveUp` 闩锁 | 非死代码（行为问题） | 已删除（随批次4b） | `stallState` 整体消失，仅剩历史注释（`:1953`） |

小结：11 条 finding —— 已删 5（F1/F5/F6/F7/F8），仍死 5（F2/F3/F4/F9/F11），部分已删 1（F10）。
最值得注意：`liveness` 信标上三个"写而不读"字段（`startedAt` / `outboundFrames` / `injectedReady`）
原样存活，是本段唯一未被清理触及的死块。
