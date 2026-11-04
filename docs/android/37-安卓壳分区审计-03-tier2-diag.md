> ⚠️ Historical audit baseline (2026-09-18, pre-cleanup). Status as of 2026-09-19: see "Status verification" section at the end. Many items listed as dead have since been deleted in batches 1–4 (commit b627610).

# tier2-diag — dead-code findings

## Scope audited

- `android-shell/app/src/main/java/com/zcode/remote/core/Tier2Probe.kt` (933 lines) — native direct-relay "tier 2" probe: pair/auth, coverage bridges, reconnect, heartbeat, discovery chain.
- `android-shell/app/src/main/java/com/zcode/remote/core/Diagnostics.kt` (91 lines) — on-device ring-buffer diagnostics + credential redaction, mirrored into `ShellLog`.
- `android-shell/app/src/main/java/com/zcode/remote/core/SurvivalVerdict.kt` (51 lines) — pure "did the background heartbeat actually run" verdict + readout line.
- `android-shell/app/src/main/java/com/zcode/remote/core/ShellLog.kt` (195 lines) — file log with rotation, crash handler, export.

Pins read: `app/src/test/java/com/zcode/remote/core/Tier2ProofTest.kt` (pins `relayProof` vectors),
`SurvivalVerdictTest.kt` (pins `verdict`, `describe`, `RESUMED_BURST_DELAY_MS`).
Reference-searched trees: all of `E:\Zcode_harmony` (so `android-shell/app` main+test, `assets/`, `res/`, `tools/`, `docs/`, `README.md`, and the parent HarmonyOS project). Repo-wide greps for `Tier2Probe` return **48 hits and zero of them are under `app/src/test`** → **`Tier2Probe` has no unit-test pin at all**; only the top-level `relayProof` is tested.

### Tier2Probe member enumeration (callers classified) — the parent asked for this explicitly

| Member | Line | Callers (file:line) | Class |
| --- | --- | --- | --- |
| `relayProof()` (top-level `internal fun`) | 20 | Tier2Probe.kt:820; Tier2ProofTest.kt:17,21,27,28 | production + test |
| `data class RelayCreds` | 28 | constructed ShellRuntime.kt:1563; read Tier2Probe.kt:352,798,820,923 | production |
| `enum Phase` | 57 | referenced only inside Tier2Probe.kt (17 refs, all internal) | internal-only type, see F3 |
| `DEFAULT_MAX_COVERAGE` | 79 | Tier2Probe.kt:517,570,574,636,644,698,719; ShellRuntime.kt:672 | production |
| `sessionsSink` | 104 | written ShellRuntime.kt:1569; read Tier2Probe.kt:488 | production |
| `discoveredCoverage()` | 155 | Tier2Probe.kt:555 **only** (ShellRuntime's call was deleted 2026-09-17 — its own comment, ShellRuntime.kt:817) | live but public visibility unused |
| `isRunning()` | 185 | ShellRuntime.kt:225,334,783,879,965,1243,1320,1559 | production |
| `progressSink` | 192 | ShellRuntime.kt:420,470 | production |
| `liveTaskSink` | 199 | ShellRuntime.kt:425,471 | production |
| `turnStateSink` | 208 | ShellRuntime.kt:440,472 | production |
| `runningSessionsProvider` | 215 | ShellRuntime.kt:460,473 | production |
| `subscribeProgress()` | 221 | ShellRuntime.kt:340 (live-progress poller) | production |
| `reanchorProgress()` | 231 | ShellRuntime.kt:336 (live-progress poller) | production |
| `start()` | 250 | ShellRuntime.kt:984 (production carrier), 1187 (`carrier_now`), 1350 (`tier2_test`) | production + experiment-command |
| `startTakeoverForTest()` | 273 | ShellRuntime.kt:1377 ← `startTier2TakeoverForTest` ← command `tier2_takeover` (ShellRuntime.kt:1144) | experiment-command only |
| `stop()` | 361 | ShellRuntime.kt:226,784,905,1321,1354 | production + experiment-command |
| private `isCurrent` | 344 | listener callbacks 792,810,881,888,895 | reached |
| private `connectNow` | 347 | `startInternal`:313, reconnect task:782 | reached |
| private `sendBootstrapThenCoverage` | 416 | `onMessage` auth_ack:846 | reached |
| private `startCoverage` | 481 | Timer task created at 471 (from 416) | reached |
| private `startCoverageRefresh` | 670 | `startCoverage`:649 | reached |
| private `stopCoverageRefresh` | 733 | `stop`:366, `startCoverageRefresh`:671 | reached |
| private `cooledDown` | 739 | 592,608,684 | reached |
| private `recordBridgeResult` | 751 | lambda passed at 516 ← `BridgeManager` RelayBridge.kt:1127 (`true`) / 1142 (`false`) | reached (both exits) |
| private `scheduleReconnect` | 768 | `onFailure`:884, `onClosed`:891 | reached |
| private `listener` (`WebSocketListener`) | 790 | OkHttp via `client.newWebSocket` (358) | reached |
| private `sendBusinessPayload` | 901 | 424,442,464,485 | reached, see F2 |
| private `sendHeartbeat` | 917 | heartbeat timer task:333-341 | reached |

**Negative result on the hints:** there is **no unreachable private helper** inside `Tier2Probe` (every one traces to a live entry point above), and I found **no superseded probe strategy left in place** — the old pairing-only "M1 probe" and the M3 coverage form are the *same* `startInternal`/`startCoverage` code with two durations (comment Tier2Probe.kt:38-45, 843), not two coexisting implementations. The only genuinely superseded-looking remnant is `stopping`, see "Suspected".

---

## Findings

### F1. `ShellLog.markProcessExit()` can never run on a real device  [UNREACHABLE]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/ShellLog.kt:99` (body at 100); call site `android-shell/app/src/main/java/com/zcode/remote/ZcodeRemoteApp.kt:74`
- **Symbol**: `ShellLog.markProcessExit`
- **Why dead**: its single call site is `override fun onTerminate()` (ZcodeRemoteApp.kt:72). Android only invokes `Application.onTerminate()` in emulated processes — the repo's own comment says so ("Only reached in emulated processes, but it keeps the log tidy."). On a production device the process is killed without running user code, so the "======== 进程结束：… ========" line can never be written. This is the one line the design doc counts on for "why did the process die?" (ShellLog.kt:20-23) — it is exactly the line that cannot fire on the device where the question is asked.
- **Reference search**: `\bmarkProcessExit\b` over `E:\Zcode_harmony` → **2 hits**: `ShellLog.kt:99` (declaration) and `ZcodeRemoteApp.kt:74` (only call). Nothing under `app/src/test`, `tools/`, `res/`, or `assets/`; the README never mentions it.
- **Confidence**: high (the only caller is a callback the platform does not deliver in production; stated in the code itself).
- **Suggested action**: delete with the `onTerminate()` override (ZcodeRemoteApp.kt:72-76).
- **Risk if removed**: none. No test references it; no other caller exists. The emulator-only "进程结束" line is lost, which is its only behaviour.

### F2. `sendBusinessPayload(payload, quiet)` — the `quiet` flag is never `true`  [VESTIGIAL]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/Tier2Probe.kt:901` (guard at 903); call sites 424, 442, 464, 485
- **Symbol**: `quiet` parameter of `sendBusinessPayload`
- **Why dead**: all four call sites pass the literal `quiet = false` (lines 433, 455, 468, 485), so the parameter is a constant and the `if (!quiet)` guard at 903 always fires. The parameter reads as if a silent send path exists (it mirrors `inject.js`'s `injectPayload(payload, quiet)`, which *does* use it at inject.js:915-926) but on the Kotlin side nothing ever selects it.
- **Reference search**: `sendBusinessPayload` over `android-shell` → 5 hits (declaration 901 + the 4 call sites); `\bquiet\b` over `Tier2Probe.kt` → 6 hits (433, 455, 468, 485, 901, 903) — **no hit passes `true`**. (`quiet` elsewhere in the repo is unrelated prose/JS: inject.js:915,926, tools tests.)
- **Confidence**: high (mechanical: every call site is in the same file).
- **Suggested action**: delete the parameter and the `if (!quiet)` wrapper, keeping the log call unconditional.
- **Risk if removed**: none — no test references `Tier2Probe` anywhere, and the parameter cannot change behaviour.

### F3. `Phase.CONNECTING` is written but no branch ever reads it  [WRITE-ONLY]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/Tier2Probe.kt:57` (declaration), assigned at `:349`
- **Symbol**: `Tier2Probe.Phase.CONNECTING`
- **Why dead**: `phase` is assigned `CONNECTING` in `connectNow()` and then only ever compared against `IDLE`, `AUTHENTICATING`, `PAIRED` and `CLOSED` (`isRunning()`:185, `isCurrent()`:345, guards 417/482/522/524/677/680, `onMessage`:833, `sendHeartbeat`:920). No code distinguishes "connecting" from "authenticating": `isRunning()` lumps both into "running", so `CONNECTING` carries no behaviour — the only place its name surfaces is the `phase=$phase` interpolation in the duplicate-start warning at :293. The 5-state machine advertises a state that is observationally free.
- **Reference search**: `Phase\.CONNECTING` over `E:\Zcode_harmony` → **1 hit** (`Tier2Probe.kt:349`, the assignment). `Phase\.(IDLE|CONNECTING|AUTHENTICATING|PAIRED|CLOSED)` over `E:\Zcode_harmony` → 17 hits, **all inside Tier2Probe.kt**; `Tier2Probe.Phase` is never referenced from any other file (the `Tier2Probe` repo-wide search lists no `Phase` use outside the object).
- **Confidence**: high for "never branched on"; medium for "worth deleting" (it is a legitimate debugging label in one log line).
- **Suggested action**: keep but annotate (one comment: "observational only, no branch reads it") — or delete the entry and log `CONNECTING` as part of `AUTHENTICATING`. Do **not** delete blindly if the 5-state naming is intended as the protocol mirror of the page's pairing FSM.
- **Risk if removed**: none functionally; no test pins it (zero test references to `Tier2Probe`). Only the `phase=$phase` text in one `warn` line changes.

### F4. `ShellLog.init(context, installCrashHandler)` — the `false` path is unreachable  [VESTIGIAL]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/ShellLog.kt:51` (parameter), guard at `:70`, private `installCrashHandler()` at `:166`
- **Symbol**: `installCrashHandler` parameter
- **Why dead**: the object is initialised exactly once, `ShellLog.init(this)` at ZcodeRemoteApp.kt:42, which uses the default `true`; no caller (production, test, or tool) ever passes `false`, so the `if (installCrashHandler)` guard at line 70 has only one reachable outcome and the parameter is a configuration switch with a single setting. The crash handler is therefore unconditional in practice — but a reader of the signature would think it can be skipped.
- **Reference search**: `installCrashHandler` over `android-shell` → 4 hits (ShellLog.kt:51 param, :70 guard, :71 call, :166 declaration) — no `false` anywhere. `ShellLog\.init` over `E:\Zcode_harmony` → **1 hit** (ZcodeRemoteApp.kt:42, no second argument).
- **Confidence**: high.
- **Suggested action**: delete the parameter (and the guard), or keep it and document that it exists only for a future test harness.
- **Risk if removed**: none; nothing passes `false`. Low value — listed for completeness, not as a priority.

---

## Known / intentional (not a new finding)

- **`tier2_test` / `tier2_stop` / `tier2_takeover`** — documented in `docs/18` §4 as kept ("实验件，接管语义复验要用"). Re-check: **reason still holds.** All three are still wired (`ShellRuntime.kt:1136-1147`), documented in the command table (README:1023), and are the *only* entry to `Tier2Probe.startTakeoverForTest` (ShellRuntime.kt:1377). I did not count the `start`/`stop`/`startTakeoverForTest` entry points or the shared experiment branches as dead.
- **`tier2_takeover` ⇒ `startTakeoverForTest`'s `onlyWorkspace = ""`** — this routes coverage through the "no page workspace reported" tier (`Tier2Probe.kt:639`), which is intentional for the takeover-semantics test, not an orphaned branch.
- **Multi-workspace / discovery-chain tier inside `startCoverage` (`coverageIsDiagnostic`, `coverageWorkspaces`, `discoveredCoverage`)** — gated by the runtime switches `multi_ws_on` / `coverage_ws:` (diagnostic commands listed in README:1015-1023), not by a constant. Reachable via adb; not reported.
- **`RelayBridge.controllerStreamEnabled = false`** — documented in `docs/18` §4; lives in `RelayBridge.kt:364`, outside my four files. Reason still holds (the `rpc:listen` self-destruct, README:461).
- **`SHELL_READ_ONLY`** — my scope contains **no** `SHELL_READ_ONLY`-sealed section: the constant exists only in `inject.js:1187` (used at inject.js:2995,3015). Likewise `AUTO_TAKEOVER_ENABLED` and `SHELL_VISIBILITY_HIJACK` no longer exist anywhere in code (README/doc mentions only), so there is no remaining Kotlin code sealed by them in my four files.
- **`Diagnostics`' diagnostics text builders** — `asText()` (Diagnostics.kt:52) is live via `SettingsActivity.kt:242` and `ShellLog.kt:110`; `SettingsActivity.buildDiagnosticsText()` (SettingsActivity.kt:226) is live via `showDiagnostics` (SettingsActivity.kt:205) ← `rowDiagnostics` click (SettingsActivity.kt:92). No removed-mechanism text builder survives.

## Suspected but unproven

- **`Tier2Probe.stopping` (line 111)** — syntactically read at 8 sites (345, 417, 482, 522, 524, 677, 680, 769, 779), but 7 of them are `stopping || phase == Phase.CLOSED`, and `stopping = true` happens **only** at line 362, immediately followed by `phase = Phase.CLOSED` at 363 — so outside a few-microsecond window inside `stop()` the two predicates are equivalent, i.e. `stopping` is a duplicate of `phase == Phase.CLOSED`. Missing evidence for a clean verdict: I cannot prove the race window (362→363) is harmless without knowing whether `WebSocketListener` callbacks can interleave there; the flag is deliberate belt-and-braces. Not proposed for deletion.
- **`Tier2Probe.reconnectAttempt` (line 112)** — it is read (772, 773), but only to throttle a log line every 6th attempt; the KDoc at line 44 promises "断线自动重连（退避 10s）" (backoff), while `delayMs` is the constant `10_000L` (771). Either the backoff was dropped and the counter is now log-only, or the doc is stale. Missing evidence: no commit history (`git log` shows a single squashed commit), so I cannot show the backoff was ever removed. Category would be `SUPERSEDED`/`VESTIGIAL`, low value.
- **`Tier2Probe.start`'s `durationMs: Long = 60_000L` default (line 252)** — every call site (ShellRuntime.kt:984, 1187, 1350) passes `durationMs` explicitly, so the default is never used; the other four defaults *are* used (ShellRuntime.kt:1350). Trivial; not worth a finding on its own.
- **`Tier2Probe.discoveredCoverage()` / `Phase` public visibility** — both are referenced only from inside `Tier2Probe` (the ShellRuntime call to `discoveredCoverage()` was deliberately deleted, ShellRuntime.kt:817). Not dead (each has a live reader), so not counted; tightening the visibility is a style call, not dead-code removal.

## Summary

- Findings: **4** (UNREACHABLE 1, VESTIGIAL 2, WRITE-ONLY 1; NO-REFS 0, TEST-ONLY 0, UNUSED-RES 0, UNUSED-DEP 0, SUPERSEDED 0).
- **No dead code found** in `Diagnostics.kt` or `SurvivalVerdict.kt`: every member of `Diagnostics` (`log`, `info`, `snapshot`, `clear`, `asText`, `redact`, `redactRemoteQuery`, `URL_WITH_QUERY`, `CAPACITY`, `entries`, `timeFormat`) has a live reader, and every `SurvivalVerdict` branch (`verdict` ×4 arms, `describe` ×3 arms, `RESUMED_BURST_DELAY_MS`) is reachable and additionally pinned by `SurvivalVerdictTest.kt` (5 tests). `ShellLog`'s level mapping has **no dead level**: `warn` 66×, `debug` 14×, `info` 11×, `error` 3× are all passed to `Diagnostics.log`/`ShellLog.append`, plus the page-supplied level at ShellRuntime.kt:1485 keeps the `else -> Log.i` arm live.
- Highest-value deletions:
  1. `ShellLog.markProcessExit` + the `onTerminate()` override — `ShellLog.kt:99` / `ZcodeRemoteApp.kt:72-76` (dead by platform contract).
  2. `sendBusinessPayload`'s `quiet` parameter and its always-true guard — `Tier2Probe.kt:901,903` (constant `false` at 433/455/468/485).
  3. `Phase.CONNECTING` — `Tier2Probe.kt:57,349` (no branch reads it; keep-and-annotate is the safer call).
- Estimated removable lines: **~13** (F1 ≈ 8: `markProcessExit` 99-101 + the `onTerminate` override 72-76; F2 ≈ 2; F3 0–1 because keep-and-annotate is the safer call; F4 ≈ 3: the guard at 70-72 plus the parameter) — this area is small in dead code; the honest result is that this probe file is well pruned.
- **Cross-scope observation (not counted in my findings, belongs to whoever audits `ShellRuntime.kt`)**: `LIVE_REANCHOR_EVERY_POLLS = 1` (ShellRuntime.kt:300) makes `livePolls % LIVE_REANCHOR_EVERY_POLLS == 0` (ShellRuntime.kt:329) **always true**, so the `if (reanchor)` guard at ShellRuntime.kt:335 is an always-true condition (a `UNREACHABLE` finding for that area). Consequence for my scope: `Tier2Probe.reanchorProgress()` is called on *every* poll, which is how I confirmed it is definitively reachable.

## Status verification (2026-09-19)

Grep scope: `android-shell/app/src`, `android-shell/app/src/main/assets`, `android-shell/tools/` (docs/miuix excluded). Tier2Probe.kt is now 927 lines. Batch attribution per the b627610 commit message.

| Item | Original verdict | Current status | Notes |
| --- | --- | --- | --- |
| F1 `ShellLog.markProcessExit` + `onTerminate()` override | UNREACHABLE | 仍在（仍死，保留并注释） | b627610 kept both and added the annotation: ShellLog.kt:104-109 KDoc now states "唯一调用方是 `Application.onTerminate()`，而平台在真机上[不回调]"; ZcodeRemoteApp.kt:72-75 unchanged. |
| F2 `sendBusinessPayload` `quiet` parameter | VESTIGIAL | 已删除 | b627610 批2. Signature is now `sendBusinessPayload(payload: JSONObject)` (Tier2Probe.kt:895); `quiet` has zero hits; the 4 call sites (:428/:445/:466/:486) pass payload only. |
| F3 `Phase.CONNECTING` | WRITE-ONLY | 仍在（仍死，保留并注释） | b627610 kept it with an explanatory KDoc at Tier2Probe.kt:58 ("[Phase.CONNECTING]（[connectNow] 里赋值、`onOpen` 才转…"); enum at :63, sole assignment at :353. The audit's keep-and-annotate option was taken. |
| F4 `ShellLog.init` `installCrashHandler` parameter | VESTIGIAL | 仍在（保留并注释） | b627610 kept it with the annotation "有意保留的开关（今天唯一调用方用默认值）" (ShellLog.kt:51); guard :74, private fn :176 unchanged. |
| Known: `tier2_test`/`tier2_stop`/`tier2_takeover` | keep | 仍在（活着） | ShellRuntime.kt:1526-1534 still wired. |
| Known: `startTakeoverForTest` `onlyWorkspace = ""` | intentional | 仍在 | Tier2Probe.kt:277-282. |
| Known: multi-workspace / discovery chain (`discoveredCoverage` etc.) | reachable via adb | 仍在（活着） | `discoveredCoverage()` :161, live reader at :549; runtime-switch-gated as before. |
| Known: `RelayBridge.controllerStreamEnabled = false` | keep (docs/18 §4) | 已删除 | b627610 批4 deleted the relay-side subscriber chain entirely; only tombstone comments remain (ShellRuntime.kt:991-992). See relay-core.md verification for what survived (RelayWire constants, ControllerTasksState via page path). |
| Known: `SHELL_READ_ONLY` | inject.js-only constant | 仍在（活着） | Still `inject.js:1169 var SHELL_READ_ONLY = true;` (referenced at :879, :1116, :1478 etc.); no Kotlin counterpart, as audited. |
| Known: `Diagnostics` text builders | live | 仍在（活着） | `asText()` called at SettingsActivity.kt:239 and ShellLog.kt:120. |
| Suspected: `Tier2Probe.stopping` duplicate flag | unproven race | 仍在 | :117 plus the same 8 guarded sites (now also `socket === webSocket` / `bridgeManager !== manager` conditions alongside). |
| Suspected: `reconnectAttempt` log-only counter | SUPERSEDED/VESTIGIAL candidate | 仍在 | :118, :764-767 — still only throttles the reconnect log every 6th attempt; no backoff. |
| Suspected: `start`'s `durationMs: Long = 60_000L` default | trivial, unused | 仍在 | :256; call sites still explicit. |
| Suspected: `discoveredCoverage()` / `Phase` public visibility | style call | 仍在 | Both still public, each with an internal-only reader. |

Tally: 14 items — 已删除 1（F2）, 仍在·保留并注释 3（F1, F3, F4）, known/suspected 10: 4 alive, 5 unchanged-as-before, 1（controllerStreamEnabled）已删除于批4（其所在文件属 relay-core 范围）.
