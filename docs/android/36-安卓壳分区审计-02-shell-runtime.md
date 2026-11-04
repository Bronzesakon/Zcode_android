> ⚠️ Historical audit baseline (2026-09-18, pre-cleanup). Status as of 2026-09-19: see "Status verification" section at the end. Many items listed as dead have since been deleted in batches 1–4 (commit b627610).

# shell-runtime — dead-code findings

## Scope audited
- `android-shell/app/src/main/java/com/zcode/remote/ShellRuntime.kt` (1872 lines, 96 KB) — the process-wide singleton glue: task store + notifier + keep-alive service, JS-bridge message dispatch, heartbeat pump, background native carrier, live-progress polling, diagnostics readout. Read in full (5 passes: 1-400, 400-799, 799-1198, 1198-1597, 1597-1872).
- Cross-file reference search covered the whole repo: `android-shell/app/src/main/java/**` (29 Kotlin files, verified by directory listing), `android-shell/app/src/test/java/**` (11 files), `android-shell/app/src/main/assets/inject.js` (3749 lines), `android-shell/app/src/main/assets/zcode-protocol.js` (2919 lines), `tools/**`, `README.md` (diag table + acceptance table), and `docs/18-全仓审计-错误路线残留与清理.md` (§2.1, §3.11, §4, §5 read directly). Everything else under `android-shell/` (incl. `docs/16`, `docs/17`, `CHANGELOG.md`) was covered by whole-tree greps only; `docs/miuix/**`, `ColorOS_docs/**`, `scratch/**` were not opened (out of scope per brief §2).

## Findings

### F1. `liveTaskSink` lambda is unreachable — the native controller stream it consumes is sealed by `controllerStreamEnabled=false`  [UNREACHABLE]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/ShellRuntime.kt:425-437` (the whole `NativeRelayClient.liveTaskSink = live@{ … }` block, 13 lines)
- **Symbol**: `ShellRuntime.startLiveProgressPolling` → the `liveTaskSink` closure, incl. `store.applyLiveTasks(tasks)` (line 429) and the `"运行态：N 个任务（running=…）"` log (431-434)
- **Why dead**: the block can only run if `NativeRelayClient.liveTaskSink` is invoked. That field is wired (`NativeRelayClient.kt:502-508`) into `BridgeManager(liveTaskSink = …)` (`RelayBridge.kt:1047`), which passes it as `BridgeSession(onLiveTasks = liveTaskSink)` (`RelayBridge.kt:1435`). `BridgeSession.onLiveTasks` has exactly **one** invocation site — `RelayBridge.kt:422` inside `onControllerWire()` — and `onControllerWire()` has exactly **one** caller: the `listenEvent(… EVENT_CONTROLLER_FRAME …)` registration at `RelayBridge.kt:388` inside `subscribeControllerTasks()`, which returns before that line because `private val controllerStreamEnabled = false` (`RelayBridge.kt:364`, guard at `:371`). The constant is a `private val` with no other writer anywhere (no setter, no reflection), so the controller frame never arrives, `onLiveTasks` never fires, and the whole lambda is unreachable — its debug line can never be written to the shell log.
- **Reference search**: `liveTaskSink|onLiveTasks` over `android-shell/app` → 9 hits: ShellRuntime.kt:425 (this lambda), ShellRuntime.kt:471 (`= null` teardown), NativeRelayClient.kt:199 (field decl), :502/:504 (the wiring that only BridgeManager reads), RelayBridge.kt:1047 (BridgeManager ctor param), :1435 (passed to BridgeSession), :124 + :422 (BridgeSession decl + the single invoke). Additionally `onControllerWire` over `android-shell/app/src/main/java` → 2 hits only (definition `RelayBridge.kt:417`, call `RelayBridge.kt:388`); `controllerStreamEnabled` over `android-shell` → 2 hits in RelayBridge.kt (`:364` decl, `:371` guard) plus README/docs prose. Tests: `onLiveTasks|liveTaskSink|liveTasks` over `app/src/test` → 4 hits, all `ControllerTasksStateTest` calling `state.liveTasks()` **directly** — no test drives `onLiveTasks`.
- **Confidence**: high
- **Suggested action**: delete the `liveTaskSink` assignment (lines 425-437) and the `NativeRelayClient.liveTaskSink = null` teardown (line 471) — or keep but annotate as "consumer of the controller stream, permanently sealed by `RelayBridge.controllerStreamEnabled=false`". Do **not** delete `NativeRelayClient.liveTaskSink` / `RelayBridge.onLiveTasks` without checking brief §5: the producer end is on the documented keep-list.
- **Risk if removed**: none at runtime — the page-side third source (`ShellRuntime.onPageControllerFrame`, line 1615-1632, fed by `post('controllertasks')` at `inject.js:823`) still calls `store.applyLiveTasks`, so `TaskStore.applyLiveTasks` and `ControllerTasksState` stay live and unit-tested. Documented in `README.md:856` and `docs/18 §3.11 B`: the three live 运行态 sources are sessions-index, `turnHeader.state` and the page's own controller/tasks-index — the native controller sink is not among them (`docs/18:537` lists only the first two legs).

### F2. `ShellRuntime.onPageStarted()` has zero callers  [NO-REFS]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/ShellRuntime.kt:1859-1862`
- **Symbol**: `fun onPageStarted()`
- **Why dead**: nothing calls it. The intended caller was `MainActivity`'s `WebViewClient.onPageStarted` (`MainActivity.kt:270-284`), which today only resets its own per-document state (`consoleLines`, `finishCallbacks`, `pageStartedAt`, page-bar BOOT colour) and re-injects — it never touches `ShellRuntime`. The body's two effects therefore never happen: `injectedReady` is never reset to `false` after the first `"ready"` message (the only *live* write is `ShellRuntime.kt:1582 = true`; the `= false` at `:1860` sits inside this dead function), and `lastInjectStatusLine` is never cleared (`:1706` is its only live write). Consequence: after a page reload, `isInjectedReady()` keeps returning `true` (stale), so the retry loops that guard on it — `dispatchJsDiag` (`:1262`) and `MainActivity.tryDiag` (`:789`) / `tryLocate` (`:720`) — no longer wait for the re-injected layer.
- **Reference search**: `onPageStarted` over the whole `android-shell` tree → 9 hits, none of them a call to `ShellRuntime.onPageStarted()`: MainActivity.kt:270 + :271 (its own `WebViewClient` override + `super` call), MainActivity.kt:102 & :403 (comments), inject.js:49 (comment), README.md:226 & :424 (docs), CHANGELOG.md:49 (docs), ShellRuntime.kt:1859 (the declaration itself). `ShellRuntime\.` over `android-shell` → 51 hits, and no `ShellRuntime.onPageStarted` among them. `ShellRuntime` over `app/src/test` → 0 hits.
- **Confidence**: high
- **Suggested action**: either delete the function, or — if the stale-ready flag matters — wire it into `MainActivity`'s `onPageStarted` override (one line) so the intent is restored. Deleting it silently keeps today's behaviour.
- **Risk if removed**: none at runtime. No test references `ShellRuntime` at all (grep `ShellRuntime` over `app/src/test` → 0 hits), so nothing pins it.

### F3. `CarrierHandback.shouldHandBack(pageLinkAlive = …)` is passed a literal `true` at its only production call site  [TEST-ONLY]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/ShellRuntime.kt:895-899` (the literal at `:896`)
- **Symbol**: `pageLinkAlive` parameter of `CarrierHandback.shouldHandBack` (`core/CarrierHandback.kt:62`), whose body is `pageLinkAlive && (!startedEarly || pageIsServing)` (`:65`)
- **Why dead (in production)**: the only production caller — `maybeStartNativeCarrier()` — reaches this line only in the `age < STALL_SILENCE_MS` branch, i.e. `pageLinkAlive` is already known to be true, so the argument is hardcoded `true`. The `pageLinkAlive == false` short-circuit is therefore exercised **only** by the JVM unit test. The parameter is a vestige of the pre-2026-09-17 shape where the caller passed `lastInboundAgeMs() < STALL_SILENCE_MS`.
- **Reference search**: `pageLinkAlive` over `android-shell` → 9 hits: ShellRuntime.kt:896 (this literal), CarrierHandback.kt:56 (KDoc), :62 (parameter), :65 (use), `CarrierHandbackTest.kt:24/:54/:65` (`true`) and `:35/:42` (`false`). No other production caller of `shouldHandBack` exists.
- **Confidence**: high
- **Suggested action**: keep but annotate (the false case is pinned by `CarrierHandbackTest."no live link means no hand-back, whatever the page reports"`), or simplify to a 2-arg function and move that test case to documentation. Cross-file: the symbol itself belongs to `core/CarrierHandback.kt` and may be reported by that file's auditor — this entry exists so the orchestrator can dedupe.
- **Risk if removed**: `CarrierHandbackTest.kt:32-47` would need updating; behaviour is unchanged either way.

### F4. 20-line comment block for the removed "后台失速自愈" mechanism, referencing a non-existent adb switch `stallguard_on`  [VESTIGIAL]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/ShellRuntime.kt:592-612` (quote of the dead reference at `:612`)
- **Symbol**: the comment section `// ---- 后台失速自愈（僵尸连接）` plus `// 默认关闭：…真机验收通过后再决定是否转正（\`stallguard_on\` 是 adb 开关）。`
- **Why dead**: the mechanism this block documents no longer exists in the file: the heartbeat pump only ever calls `maybeStartNativeCarrier()` (`:555`), never `nudgePageRecovery`; there is no `stallguard` flag, no `stallguard_on` (or `stallguard_off`) branch in `runNativeDiag` (`:1134-1249`), and no nudge counter/`lastNudgeAt` state (deleted in the round documented at `docs/18 §2.1`). The design it describes was superseded by the native carrier ("方案 B 落地", the next comment block at `:614`), which is the live path. `nudgePageRecovery` itself survives only as the adb diagnostic hook (`nudge_now` / `nudge_close`), which `docs/18 §4` explicitly keeps.
- **Reference search**: `stallguard` over the whole `android-shell` tree → 1 hit (this comment, ShellRuntime.kt:612). `nudge_now|nudge_close|nudgePageRecovery` → ShellRuntime.kt:1199/:1204/:1201/:1206/:1100 plus `docs/18 §4` and `README.md:1011` (both document them as pure diagnostics); no non-adb caller. `maybeNudgeStalledPage|nudgeEscalated|pendingNudgeAt|lastNudgeAt` → 0 hits (already cleaned).
- **Confidence**: high
- **Suggested action**: keep but annotate — rewrite the block to say the stall-nudge path was superseded by the native carrier, or delete lines 592-612 (the live carrier rationale is already documented at `:614-633`).
- **Risk if removed**: comment-only; no behaviour. Careful not to delete the `nudgePageRecovery` KDoc at `:1096-1099`, which describes the surviving adb diagnostic.

### F5. Unused import `com.zcode.remote.core.TaskStatus`  [NO-REFS]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/ShellRuntime.kt:20`
- **Symbol**: `import com.zcode.remote.core.TaskStatus`
- **Why dead**: the fully-qualified name `TaskStatus` is never used in this file. `onSessions` builds `TaskSnapshot(phase = item.optString("phase"))` (a raw string, `:1648`) and `onLiveness`/`onStatus` never mention it; status classification lives in `NotifyState`/`Notifier`.
- **Reference search**: `TaskStatus` over the whole Kotlin tree → 13 hits: ShellRuntime.kt:20 **only** (the import), NotifyState.kt:37/:238/:242/:243/:267-270 (declaration + users), Notifier.kt:17/:58, PromotionPolicy.kt:59, TaskStore.kt:31. The import in ShellRuntime is the sole occurrence inside the file.
- **Confidence**: high
- **Suggested action**: delete the import line.
- **Risk if removed**: none; Kotlin only warns on unused imports, and `tools/check_kotlin_structure.py` does not check them (verified by reading the script) — which is why this survived the previous cleaning round.

### F6. Three null-guards that can never be taken  [UNREACHABLE]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/ShellRuntime.kt:179`, `:772`, `:1373-1376`
- **Symbol**: `val current = liveness ?: return` (in `onLiveness`); `val snapshot = liveness ?: return -1L` (in `nudgeMark`); `val c = relayCreds ?: run { …; return }` (in `startCarrierTakeoverForTest`)
- **Why dead**:
  1. `:179` runs two statements after `liveness = Liveness(…)` at `:161`; `liveness` is written in exactly one place and always non-null, so the elvis branch cannot be taken.
  2. `:772` — its only call site is `:859` inside `maybeStartNativeCarrier`, which already returned at `:843` if `liveness == null` (`lastInboundAgeMs()` returns null only when `liveness == null` or when no frame ever arrived; the latter still leaves `liveness` non-null), so `?: return -1L` is unreachable.
  3. `:1373-1376` — the function returned at `:1362-1372` when `relayCreds == null`; `relayCreds` is assigned in exactly one place (`:1563`, always a non-null `RelayCreds`), never nulled. The `Diagnostics.log("warn", "承载: 凭证未就绪，无法启动接管验证")` at `:1374` can therefore never be printed.
- **Reference search**: `relayCreds` over ShellRuntime.kt → 9 hits: `:1289` (decl `= null`), `:1563` (the only assignment, non-null), reads at `:966`, `:1173`, `:1345`, `:1362`, `:1373` (no `relayCreds = null` write anywhere). `liveness =` over ShellRuntime.kt → 1 hit (`:161`); `liveness` reads at `:153`, `:179`, `:264`, `:772`, `:888`, `:934`. `nudgeMark` → `:771` (decl) + `:859` (only caller, preceded by the `lastInboundAgeMs() ?: return` guard at `:843`).
- **Confidence**: high (mechanically provable; `@Volatile` does not help — no writer ever stores null)
- **Suggested action**: keep but annotate (defensive) or delete the three guards; case 3 is worth deleting because it also removes a log line that can never be observed.
- **Risk if removed**: none. These are single-threaded null checks around state whose writers only ever publish non-null values.

### F7. `reanchor` is a constant-true guard because `LIVE_REANCHOR_EVERY_POLLS = 1`  [UNREACHABLE]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/ShellRuntime.kt:329` (guard), constant at `:300`
- **Symbol**: `val reanchor = livePolls % LIVE_REANCHOR_EVERY_POLLS == 0` → `if (reanchor) NativeRelayClient.reanchorProgress()` (`:335-337`), plus the `livePolls` counter it exists for (`:306`, `:327`)
- **Why dead**: `x % 1 == 0` for every integer, so with `LIVE_REANCHOR_EVERY_POLLS = 1` (the 2026-09-17 拍板 value) the condition is always true; the "skip the re-anchor this tick" path can never execute. Only the tuning constant keeps the branch meaningful.
- **Reference search**: `reanchor|LIVE_REANCHOR_EVERY_POLLS|livePolls` over ShellRuntime.kt → `:300` (const decl, with the 2/1/4 trade-off documented), `:306` (decl), `:327` (increment), `:329` (this guard), `:335-337` (body). Constant is not overridden anywhere (no build-config/`BuildConfig` indirection).
- **Confidence**: high
- **Suggested action**: keep but annotate — the guard is a deliberate knob whose current value (1) makes it a no-op; alternatively inline the re-anchor and keep the constant for the rotation cadence only.
- **Risk if removed**: low; the constant is documented as user-tuned ("`1`（12s 轮换，**现行**）"), so this is parametrisation, not rot. Do not "fix" the constant.

### F8. Default argument values that no caller ever uses (3 sites)  [VESTIGIAL]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/ShellRuntime.kt:1100` (`mode: String = "event"`), `:1344` (`durationMs: Long = 60_000L`), `:1361` (`autoStopMs: Long = 90_000L`)
- **Symbol**: `nudgePageRecovery(reason, mode)`, `startNativeRelayClient(durationMs)`, `startCarrierTakeoverForTest(autoStopMs, attempt)`
- **Why dead**: every call site passes the default's own value explicitly, so the default expressions are never evaluated: `nudgePageRecovery("adb", "event")` (`:1201`) and `nudgePageRecovery("adb-close", "close")` (`:1206`); `startNativeRelayClient(60_000L)` (`:1137`); `startCarrierTakeoverForTest(90_000L)` (`:1145`) and the recursive `startCarrierTakeoverForTest(autoStopMs, attempt + 1)` (`:1369`). Only `attempt = 1` is genuinely used as a default.
- **Reference search**: `nudgePageRecovery` → `:1100` (decl), `:1201`, `:1206` (all call sites; docs/18 §4 documents them); `startNativeRelayClient` → `:1343` (comment), `:1344` (decl), `:1137` (only call, explicit); `startCarrierTakeoverForTest` → `:1361` (decl), `:1145` (explicit), `:1369` (explicit for `autoStopMs`). No caller omits these arguments.
- **Confidence**: high
- **Suggested action**: keep but annotate, or turn the values into named constants (`TIER2_TEST_MS`, `TIER2_TAKEOVER_MS`) — deleting the defaults is cosmetic.
- **Risk if removed**: none; the carrier / nudge commands are on the intentional keep-list (docs/18 §4), so do not delete the functions, only the never-taken defaults.

### F9. Redundant dead store `appIsForeground = true`  [WRITE-ONLY]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/ShellRuntime.kt:216`
- **Symbol**: `appIsForeground = true` inside `if (foreground) { … }` of `onAppForegroundChanged`
- **Why dead**: line 214 already executed `appIsForeground = foreground`, and line 215 guarantees `foreground == true`; the second assignment therefore cannot change the value and has no observable effect (the only reader is `userIsAway()`, `:1299`).
- **Reference search**: `appIsForeground` over `android-shell` → 6 hits, all in ShellRuntime.kt: `:214` (assign from param), `:216` (this assignment), `:260` (assign false), `:533` (decl `= true`), `:1299` (the only read, in `userIsAway()`). No other reader, so the store has no observable effect.
- **Confidence**: high
- **Suggested action**: delete the line.
- **Risk if removed**: none.

### F10. `liveness` payload fields sent by JS that Kotlin never reads — cross-file (may overlap the inject.js auditor)  [WRITE-ONLY]
- **Location**: `android-shell/app/src/main/assets/inject.js:1386-1387` (`deviceSidKnown`, `injectedReady`); `:1376` (`outboundFrames`, redundant with the perf report's own use)
- **Symbol**: the `post('liveness', {…})` payload consumed at `ShellRuntime.kt:158-172`
- **Why dead**: `ShellRuntime.onLiveness` reads exactly 10 keys (`lastInboundAgoMs`, `inboundFrames`, `pairAcks`, `socketsOpened`, `socketsClosed`, `backgroundTicks`, `backgroundFirstTickDelayMs`, `paired`, `socketState`, `convFrameAgoMs`). The three extra keys in the payload are never read on the Kotlin side, and `deviceSidKnown` / `injectedReady` are computed at the post site only for this message.
- **Reference search**: `deviceSidKnown|injectedReady` over `android-shell` → inject.js (the liveness payload construction and its own locals) and `ShellRuntime.isInjectedReady()` (a Kotlin field, unrelated symbol) — no reader of the JSON keys; `outboundFrames` → inject.js perf block + the liveness payload, no Kotlin reader (`ShellRuntime.kt` has no `outboundFrames`). `inboundFrames|pairAcks|socketsOpened|socketsClosed|backgroundTicks|backgroundFirstTickDelayMs|lastInboundAgoMs|convFrameAgoMs|paired|socketState` over ShellRuntime.kt → all present at `:160-171`.
- **Confidence**: high (the Kotlin side of the claim); medium for "dead" in the JS sense, since another consumer could appear later
- **Suggested action**: keep but annotate / hand to the `inject.js` owner — deleting the two computed fields is a JS-area edit and outside this file's scope; listed here only because the deadness is defined by this file's consumer.
- **Risk if removed**: none on the Kotlin side (Kotlin uses `opt*` with defaults). Low priority.

## Command / event dispatch audit (negative result — no finding)

The JS→Kotlin dispatch chain in this file is `onBridgeMessage`'s `when (root.optString("event"))` (`ShellRuntime.kt:1479-1589`). Every branch has a JS sender and every JS sender has a branch — the map is exactly 1:1, so **there is no dead command branch in this file**:

| event id | Kotlin branch | JS sender |
| --- | --- | --- |
| `sessions` | `:1480` | `inject.js:814` |
| `status` | `:1481` | `inject.js:848` (payload `bridges`/`passive`/`reason` built at `zcode-protocol.js:1319-1323`) |
| `liveness` | `:1482` | `inject.js:1373` |
| `diag` | `:1483` | `inject.js:80`, `:3092` |
| `pagelog` | `:1487` | `inject.js:177` |
| `convtext` | `:1505` | `inject.js:673` (fed by the **passive** path `zcode-protocol.js:2579`, not by the sealed self-subscribe stream at `:1879`) |
| `pagews` | `:1518` | `inject.js:1078` |
| `controllertasks` | `:1529` | `inject.js:823` ← `zcode-protocol.js:2589` (`_forwardControllerTasks`), envelope `{kind:'complete', frame}` matches `ControllerTasksState.applyWire` |
| `pagevitals` | `:1540` | `inject.js:2101` (`postPageVitals`, called from the still-running watchdog at `:2173`/`:2260`/`:2283`) |
| `relaycreds` | `:1548` | `inject.js:282` |
| `pagestate` | `:1572` | `inject.js:3211` |
| `ready` | `:1579` | `inject.js:3733` |

Search: `post\('[a-zA-Z]+'` over `app/src/main/assets/inject.js` → 13 hits = the 12 ids above (`diag` twice) and nothing else, so the `else -> Unit` arm drops nothing.

The **native** command chain `runNativeDiag` (`ShellRuntime.kt:1119-1250`) is adb-driven, not JS-driven (`DiagReceiver.kt:39-40`, `MainActivity.kt:754-785`), and all 16 ids it handles are documented as adb switches in `README.md:1008-1024` and each has a reachable trigger: `coverage_ws:`/`coverage_ws_clear` (`:1119`,`:1129`), `carrier_test`/`carrier_stop`/`carrier_takeover` (`:1136`/`:1140`/`:1144`), `carrier_on`/`carrier_off`/`carrier_now` (`:1149`/`:1153`/`:1171`), `multi_ws_on`/`multi_ws_off` (`:1158`/`:1166`), `nudge_now`/`nudge_close` (`:1199`/`:1204`), `net_probe` (`:1209`), `wvdebug_on`/`wvdebug_off` (`:1216`/`:1229`), `stall_state` (`:1238`). Nothing in JS sends any of these ids, and nothing needs to — the README documents them as the human/adb surface (`README.md:1008-1024`, the only place they are recorded — `tools/cdp.mjs:13-14` repeats the broadcast invocation and `wvdebug_on`; no script sends `carrier_*` / `carrier_*` / `stall_state` / `net_probe`, they are typed by hand). Reported as a negative, **not** as findings, because an adb-reachable branch is not dead; what *is* flagged is that three of these adb-only functions take defaults no caller uses (F8).

Also verified clean in this file (no finding, evidence recorded so the orchestrator can trust the negative): no constant-`false`/`true` capability switch remains (`AUTO_TAKEOVER_ENABLED` / `SHELL_VISIBILITY_HIJACK` / `SHELL_READ_ONLY` → grep over `android-shell` shows 0 hits in ShellRuntime.kt; only comments at `:683` mention `controllerStreamEnabled`); every state field has at least one read (no write-only counters/timestamps/flags/token strings remain after the `rendererProbeToken`/`lastLivenessAt` cleanup recorded in `docs/18 §2.1`); every `evaluateJavascript` target exists in `inject.js` (`__zcodeShellSetAppForeground:2963`, `__zcodeShellHeartbeat:1503`, `__zcodeShellReportLiveness:3112`, `__zcodeShellNudgeRecover:2531`, `__zcodeShellAfterCarrierReturn:2615`, `__zcodeShellDiag:2898`); all 22 imports except `TaskStatus` (F5) are used.

## Known / intentional (not a new finding)
- `nudgePageRecovery` (`:1100`) + `nudge_now` / `nudge_close` (`:1199`, `:1204`) — documented in `docs/18 §4` as "现在是它唯一的使用者；纯取证诊断". Re-check: reason **still holds** — the pump no longer nudges (`:555` calls only `maybeStartNativeCarrier`), and the adb branches are the only callers. Not re-reported; F4 covers only the stale comment block above them.
- `carrier_test` / `carrier_stop` / `carrier_takeover` (`:1136`, `:1140`, `:1144`) plus `startNativeRelayClient` / `stopNativeRelayClient` / `startCarrierTakeoverForTest` — `docs/18 §4` keeps them for takeover re-verification. Re-check: **holds** — reachable via the documented adb broadcast (`DiagReceiver.kt:39-40`) or `am start … --es diag_cmd` (`MainActivity.kt:754-759`); note that no tool script drives them (`diag_cmd|carrier|carrier|stall_state|net_probe` over `tools/` → 2 hits, both the usage comment in `tools/cdp.mjs:13-14`), so they are hand-typed switches. Only their never-used default arguments are flagged (F8).
- The `controller` protocol chain (`RelayBridge.controllerStreamEnabled=false`, `ControllerTasksState`, `TaskStore.applyLiveTasks`) — `docs/18 §4` keeps it as the protocol-schema / unit-test reference. Re-check: **holds**, and it is the *producer* of F1. `ShellRuntime`'s page-side consumer of the same state (`:1611-1632`) is the live route and must not be touched.
- `TaskStore.Workspace.source` — `docs/18 §4` keeps it as a wire-isomorphic shape field; `ShellRuntime.kt:1662` is one of its writers/readers (`data.optString("source")` ← `zcode-protocol.js:1788`). Re-check: **holds** (the JS still sends it).
- Injected-layer watchdog scaffolding (`inject.js` arm/patrol/beacon, `docs/18 §5`) — the Kotlin consumer is the log-only `"pagevitals"` branch (`ShellRuntime.kt:1540-1547`). Re-check: **holds** — the watchdog still arms/cancels and still calls `postPageVitals` (`inject.js:2173`, `:2260`, `:2283`), so the branch is reachable even though both of its "terminal" actions (`nudgeReconnect:2244`, `stallReloadIfAllowed:2252`) are now stubs. Deleting the Kotlin branch would be wrong while the beacons still arrive.
- `docs/18 §5` items that live elsewhere (`Prefs.subscribeAllWorkspaces`, `zcode-protocol.js` self-subscribe `CONVERSATION_SUBSCRIBE_ENABLED=false`, `RelayWire.METHOD_ROWS_RANGE`, `MODE_SAVE`) — no new occurrences found in this file; `ShellRuntime` reads none of them.

## Suspected but unproven
- `ShellRuntime.acceptNativeSessions` (`:1596`) — proven-alive only through the `NativeRelayClient.sessionsSink = { update -> acceptNativeSessions(update) }` lambda at `:1569`, i.e. a public function with a single internal caller that itself just forwards to `onSessions`. Not dead (the sink is invoked from `RelayBridge.kt:249` via `BridgeManager`), but it is an unnecessary public hop. Missing evidence for any "dead" claim: none — listed only as a simplification candidate, not a dead-code claim.
- `ShellRuntime.nativeNetProbe(tag)` (`:1057`) / `nudgePageRecovery(reason, …)` (`:1100`) parameters — `tag` is always the literal `"adb"` (`:1210`) and `reason` only reaches a warn log on the failure path (`:1102`). Log-only labels rather than dead state; not counted as findings because both values are still read.

## Summary
- Findings: 10 (UNREACHABLE 3 — F1, F6, F7; NO-REFS 2 — F2, F5; VESTIGIAL 2 — F4, F8; TEST-ONLY 1 — F3; WRITE-ONLY 2 — F9, F10).
- Highest-value deletions:
  1. `ShellRuntime.kt:425-437` — `NativeRelayClient.liveTaskSink` lambda, unreachable because `RelayBridge.controllerStreamEnabled=false` seals its only producer (`RelayBridge.kt:364/371 → 388 → 417 → 422`); 13 dead lines incl. a log line that can never print.
  2. `ShellRuntime.kt:1859-1862` — `onPageStarted()` has zero callers; its `injectedReady`/`lastInjectStatusLine` resets never happen, so the readiness guard is permanently stale after the first page load.
  3. `ShellRuntime.kt:592-612` — 21-line comment block for the removed 失速自愈 mechanism, pointing at the non-existent adb switch `stallguard_on` (0 handlers repo-wide).
- Estimated removable lines: ~45 (13 code in F1, 8 code in F2/F5/F6/F7/F9, ~21 comment lines in F4; F3/F8/F10 are signature/payload-level, 0 lines).
- Negative results worth recording: the 12-id JS→Kotlin event map is exactly 1:1 (no dead dispatch branch); all 16 native adb command ids are documented and reachable; no constant-false capability switch and no write-only field survive in this file.

## Status verification (2026-09-19)

Grep scope: `android-shell/app/src`, `android-shell/app/src/main/assets`, `android-shell/tools/` (docs/miuix excluded). ShellRuntime.kt is now 2305 lines. Batch attribution per the b627610 commit message (批1 接回注入就绪位复位；批2 机械清理；批3 合并诊断排队；批4 删 controller 接线/看门狗决策机/桥路由).

| Item | Original verdict | Current status | Notes |
| --- | --- | --- | --- |
| F1 `NativeRelayClient.liveTaskSink` lambda (ShellRuntime 425-437) | UNREACHABLE | 已删除 | b627610 批4 removed the entire chain (lambda, teardown, `NativeRelayClient.liveTaskSink`, `BridgeManager.liveTaskSink`, `BridgeSession.onLiveTasks`) — all zero hits in app/src; only a tombstone comment remains (tools/protocol.test.js:876). |
| F2 `ShellRuntime.onPageStarted()` zero callers | NO-REFS | **复活（已接回）** | b627610 批1 wired it: MainActivity.kt:297 (`WebViewClient.onPageStarted`) and :824 now call `ShellRuntime.onPageStarted()`; body (:2292-2296) resets `injectionReadiness` + `lastInjectStatusLine`; readiness state extracted into `core/InjectionReadiness.kt` (new file, unit-tested). The original dead function is now live, and the stale-ready bug is fixed. |
| F3 `CarrierHandback.shouldHandBack` literal `pageLinkAlive = true` | TEST-ONLY | 仍在（仍死） | Unchanged: ShellRuntime.kt:1265-1266 still passes `pageLinkAlive = true`; CarrierHandback.kt:62/65 untouched. |
| F4 20-line stallguard comment block (`stallguard_on`) | VESTIGIAL | 已删除 | b627610. `stallguard` → 0 hits in app/src/tools. |
| F5 unused import `core.TaskStatus` | NO-REFS | 仍在（仍死） | Survived all four batches: still the sole occurrence in ShellRuntime.kt (:22). |
| F6 three never-taken null-guards | UNREACHABLE | 仍在（仍死） | All three unchanged: `liveness ?:` at :203 and :1138 (`nudgeMark`), `relayCreds ?:` at :1786 (`startCarrierTakeoverForTest`). |
| F7 `reanchor` constant-true guard (`LIVE_REANCHOR_EVERY_POLLS = 1`) | UNREACHABLE | 仍在（仍死，有意旋钮） | Unchanged: const = 1 at :638, guard at :671; keep-as-knob decision held. |
| F8 three never-used default arguments | VESTIGIAL | 仍在（仍死） | Unchanged: `nudgePageRecovery(mode)` :1489, `startNativeRelayClient(durationMs)` :1756, `startCarrierTakeoverForTest(autoStopMs)` :1774; all call sites still explicit (:1526, :1534, :1590, :1595, :1782). |
| F9 redundant `appIsForeground = true` dead store | WRITE-ONLY | 仍在（仍死） | Unchanged: :489 assigns from param, :495 re-assigns `true` inside `if (foreground)`. |
| F10 liveness payload keys Kotlin never reads (`deviceSidKnown`/`injectedReady`/`outboundFrames`) | WRITE-ONLY | 仍在（仍死） | inject.js still sends them (:1358 `outboundFrames`, :1368 `deviceSidKnown`); `onLiveness` still reads the same 10 keys, none of the three. |
| Known: `nudgePageRecovery` + `nudge_now`/`nudge_close` | intentional keep | 仍在（活着） | ShellRuntime.kt:1489 (def), :1588-1595 (adb branches). |
| Known: `carrier_test`/`carrier_stop`/`carrier_takeover` | intentional keep | 仍在（活着） | ShellRuntime.kt:1526-1534 route into `startNativeRelayClient`/`startCarrierTakeoverForTest`. |
| Known: controller protocol chain | keep (producer of F1) | 已删除（生产端）/ 仍活（消费端） | Relay-side producer deleted in 批4 (see relay-core.md verification). Page-side third source kept and live: `pageControllerTasks` :2034 → `TaskStore.applyLiveTasks` :2054. |
| Known: `TaskStore.Workspace.source` | keep (wire shape) | 仍在（活着） | Still read/written at ShellRuntime.kt:2084 (`source = data.optString("source")`). |
| Known: pagevitals watchdog branch | keep (beacons still arrive) | 仍在（活着） | `pagevitals` branch at :1961. |
| Suspected: `acceptNativeSessions` single-hop simplification | not a dead claim | 仍在 | Same shape: sink wiring :1992, def :2019. |
| Suspected: `nativeNetProbe(tag)` / `nudgePageRecovery(reason)` log-only params | not counted | 仍在 | :1599 still `nativeNetProbe("adb")`. |

Tally: 17 items — 已删除 2（F1, F4）, 复活 1（F2 — 原"死"条目现已被批1接回并成为唯一就绪位复位点）, 仍在·仍死 7（F3, F5–F10）, known/suspected 7 unchanged (5 alive/kept, 2 as-is).
