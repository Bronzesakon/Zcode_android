> ⚠️ Historical audit baseline (2026-09-18, pre-cleanup). Status as of 2026-09-19: see "Status verification" section at the end. Many items listed as dead have since been deleted in batches 1–4 (commit b627610).

# tests-tools — dead-code findings

Area: all Kotlin unit tests (`android-shell/app/src/test/java/com/zcode/remote/core/*.kt`) and all
of `android-shell/tools/`. Read-only audit; nothing under `app/` or `tools/` was modified.
Commands actually run (evidence): `node tools/protocol.test.js`, `node tools/inject.test.js`,
`python tools/doc_snapshot.py --help`, `python tools/doc_snapshot.py --force 03 --probe`,
`git ls-files`, `git check-ignore`.

## Scope audited

Kotlin unit tests — `android-shell/app/src/test/java/com/zcode/remote/core/` (11 files, 2135 lines, 111 `@Test`):

- `NotifyStateTest.kt` (496) — notification decision logic + `TaskStore` update semantics
- `RelayBridgeTest.kt` (497) — Tier2 bridge engine + sessions-index state machine (scripted desktop)
- `RelayWireTest.kt` (375) — wire codec golden bytes, fragment assembly, conversation tail
- `ControllerTasksStateTest.kt` (331) — controller/tasks-index state machine + three-source priority
- `PromotionPolicyTest.kt` (128) — Live-Updates slot allocation
- `CarrierHandbackTest.kt` (94) — carrier hand-back rule
- `SurvivalVerdictTest.kt` (89) — milestone-4 survival readout
- `PageBarColorTest.kt` (85) — fixed status-bar colour table
- `UploadMimeTest.kt` (72) — accept-type normalisation
- `PreviewSettleTest.kt` (41) — card-body settle window
- `Tier2ProofTest.kt` (31) — `relayProof` HMAC regression anchor

Tools — `android-shell/tools/` (11 files, 4821 lines):

- `inject.test.js` (1745) — injected-layer tests against a simulated page (45 `test()`)
- `protocol.test.js` (1214) — wire protocol + `RemoteClient` tests (52 `test()`)
- `doc_snapshot.py` (557) — vendor doc snapshotter
- `watch_ci.py` (291) — anonymous CI status reader
- `fake-desktop.js` (233) — shared scripted desktop fixture for both Node suites
- `cdp.mjs` (229) — CDP driver for the on-device WebView
- `cdp-capture.mjs` (194) — CDP Network-domain frame capture
- `check_kotlin_structure.py` (108) — brace/package/duplicate sanity check
- `device_check.sh` (102) — on-device acceptance sweep
- `check_resources.py` (85) — `@type/name` reference check
- `notif_dump.py` (53) — `dumpsys notification` field printer

---

## Findings

### F1. `workspaceId` is still written into the test snapshot fixture that `docs/18` §2.4 says was deleted  [VESTIGIAL]
- **Location**: `android-shell/app/src/test/java/com/zcode/remote/core/RelayBridgeTest.kt:469`
  (same inert key also in `tools/fake-desktop.js:227` and `tools/protocol.test.js:314`)
- **Symbol**: `snapshotWire(...)` fixture key `"workspaceId"`
- **Why dead**: `docs/18-全仓审计…md` §2.4 records the exact cleanup —
  "`RelayBridgeTest.kt`：删掉 `assertTrue(state.ready)` 与 snapshot 里的 `workspaceId`（钉的是已删字段）".
  `assertTrue(state.ready)` is indeed gone, but the `workspaceId` key is **still there**, and the Kotlin
  reader ignores it: `SessionsIndexState.applyLogical` (`SessionsIndexState.kt:139-149`) reads only
  `logEpoch` and `sessions` from the snapshot object. Nothing in Kotlin ever reads `workspaceId`.
- **Reference search**: `\bworkspaceId\b` over `E:\Zcode_harmony` → 8 hits:
  `RelayBridgeTest.kt:469` (fixture write), `RelayWireTest.kt:89` (inside a frozen golden hex literal) and
  `:98` (assert on the value decoded from that literal), `fake-desktop.js:227` (fixture write),
  `protocol.test.js:314` (hand-written frame for a fragment test that only asserts `list().length`),
  `zcode-protocol.js:817` + `:894` (**writes only** — the JS field is never read either).
  The other ~47 hits for `workspaceId` in the repo are the different identifier `workspaceIdentity`.
  Net: zero readers on the Kotlin side, zero readers of the stored JS field.
- **Confidence**: high (that the key is inert and docs/18 §2.4's claim does not match the file);
  medium (on *why* — the repo has a single squashed commit `f82277d`, so I cannot tell whether the
  §2.4 edit was reverted or never applied).
- **Suggested action**: delete the three fixture writes (or, if the fixture is deliberately kept
  wire-isomorphic, annotate them as "field the shell does not read").
- **Risk if removed**: none — no assertion anywhere depends on `workspaceId`.
- **Note for the orchestrator**: the JS half (`zcode-protocol.js:817/894`, write-only `this.workspaceId`)
  belongs to the assets area; I only record the pointer here.

### F2. `doc_snapshot.py`: the JS-rendered-snapshot table and its only consumer are both unreachable  [NO-REFS]
- **Location**: `android-shell/tools/doc_snapshot.py:247` (`RENDERED_ENTRIES`, lines 245–278) and
  `android-shell/tools/doc_snapshot.py:488` (`register_rendered`, lines 488–499)
- **Symbol**: `RENDERED_ENTRIES` (module-level list of 6 `m3.material.io` entries) and
  `def register_rendered(records)`
- **Why dead**: `register_rendered` is the function that consumes `RENDERED_ENTRIES`-shaped records, and
  it has **no call site** — `main()` (lines 502–553) only iterates `MANIFEST_ENTRIES`. `RENDERED_ENTRIES`
  itself is never read by anything. The pair is a matched dead set: a data table plus the writer that
  was supposed to fold those records into `docs/_manifest.json`.
- **Reference search**: `RENDERED_ENTRIES|register_rendered` over `E:\Zcode_harmony` → **2 hits, both
  declaration-only** (`doc_snapshot.py:247`, `doc_snapshot.py:488`). Also checked the docs tree and
  `docs/_manifest.json` references by filename: nothing calls into `doc_snapshot` programmatically.
- **Confidence**: high.
- **Suggested action**: delete both (44 lines), or — if the 6 rendered entries are wanted as
  documentation — convert the block to a comment and delete `register_rendered`.
- **Risk if removed**: the 6 `m3.material.io` snapshots already exist on disk and are already registered
  in `docs/_manifest.json`; only the *re-registration* path disappears, and it is unreachable today.

### F3. `doc_snapshot.py`: the documented `--force 03` invocation is an argparse error; `--only` is undocumented  [VESTIGIAL]
- **Location**: `android-shell/tools/doc_snapshot.py:31` (module docstring) vs `:505-506` (real flags)
- **Symbol**: usage line `python tools/doc_snapshot.py --force 03     # refresh one subdirectory`
- **Why dead**: `--force` is `action="store_true"` (line 505) and the parser defines **no positional
  argument**, so the documented form cannot run; the subdirectory filter is `--only` (line 506), which
  the docstring never mentions.
- **Reference search / evidence**: ran `python tools/doc_snapshot.py --help` → usage is
  `[-h] [--probe] [--force] [--only ONLY]` (no positionals); ran
  `python tools/doc_snapshot.py --force 03 --probe` → `error: unrecognized arguments: 03`, **exit code 2**.
  `README.md:723` only documents `--probe`, so the wrong form is quoted nowhere else — the rot is
  self-contained in the tool's own docstring.
- **Confidence**: high (executed).
- **Suggested action**: fix the docstring to `--force --only 03`, or add an optional positional.
- **Risk if removed**: none (string only).

### F4. `fake-desktop.js`: `acked` and `openCount` are written and never read  [WRITE-ONLY]
- **Location**: `android-shell/tools/fake-desktop.js:62` + `:118` (`acked`), `:68` + `:87` (`openCount`)
- **Symbol**: `DesktopCore.acked`, `DesktopCore.openCount`
- **Why dead**: both are bookkeeping fields of the shared test fixture. `acked` is only ever pushed to
  (on `rpc-frame-ack`); `openCount` is only ever incremented (on `workspace-bridge-open`). No test and no
  other tool reads either one.
- **Reference search**: `\.acked|\.openCount` over `android-shell` → 4 hits, all inside
  `fake-desktop.js` (init at 62/68, writes at 118/87); the only other `ack*` hits are the unrelated test
  titles at `protocol.test.js:933/1030`. `grep acked|openCount` over `tools/*.js` → same 6 hits, no reader.
- **Contrast (this is what makes them look alive)**: the three sibling counters in the same constructor
  *are* read by tests — `sent` (`protocol.test.js:402/421/457/466`), `subscriptions`, `failingWorkspaces`
  (`:410`) — so the fixture does expose state for assertions; `acked`/`openCount` simply never joined them.
- **Confidence**: high.
- **Suggested action**: delete the two fields and their two writes (4 lines).
- **Risk if removed**: none; nothing asserts on them. (Note: `RelayBridgeTest.kt:137` asserts on the
  *Kotlin* scripted desktop's `acks`, which is a separate implementation and stays.)

### F5. `fake-desktop.js`: `subscribeDelayMs` is a knob no test ever turns  [VESTIGIAL]
- **Location**: `android-shell/tools/fake-desktop.js:64` (declaration) and `:176-180` (only read)
- **Symbol**: `DesktopCore.subscribeDelayMs`
- **Why dead**: the field defaults to `0` and gates a `setTimeout` branch in the `subscribeSessionsIndexV4`
  reply. No test (and nothing else) ever assigns it, so the delayed-reply path — written to simulate a
  slow desktop — has never executed since the fixture was introduced.
- **Reference search**: `subscribeDelayMs` over `android-shell` → **3 hits, all inside
  `fake-desktop.js`** (decl 64, read 176, arg 177). No `.test.js` hit, no doc hit.
- **Confidence**: high that nothing sets it; medium that it is not wanted (a future test *could* set it —
  it is a public field on a fixture). Recommend "delete, or add the test that uses it".
- **Suggested action**: delete with F4, or keep and add the slow-subscribe test it was written for.
- **Risk if removed**: none today.

### F6. `inject.test.js`: three members of the fake page environment have zero readers  [NO-REFS]
- **Location**: `android-shell/tools/inject.test.js:99` (`hasAttribute`), `:72` (`isConnected`), `:210` (`protocols`)
- **Symbol**: `FakeElement.prototype.hasAttribute`, `FakeElement.isConnected`, `FakeWebSocket.protocols`
- **Why dead**: the fake DOM/socket exists to be called by `inject.js` and asserted on by tests. These
  three are called by neither: `inject.js` reads `getAttribute` (guarded, e.g. `:1526`, `:1743`, `:1769`,
  `:2773`) and `tagName` (`:1523`, `:1765`), never `hasAttribute`; it never probes `isConnected`; and the
  `protocols` argument of the socket wrapper is passed to the *native* constructor inside
  `inject.js:307-310`, never read off the fake instance.
- **Reference search**: `hasAttribute|isConnected|\.protocols\b` over all of `E:\Zcode_harmony` →
  **3 hits, all declarations in `inject.test.js`**. Cross-checked that the neighbouring fake members are
  genuinely used by `inject.js` (`nodeType` at `inject.js:1520`/`:3662`, `tagName` at `:1523`/`:1765`,
  `childElementCount` at `:1578`/`:1725`, `textContent` at `:1581`, `parentElement` at `:1590`,
  `scrollIntoView` at `:1543`, `getComputedStyle` at `:1531`), which is why only these three are dead.
- **Confidence**: high.
- **Suggested action**: delete the three (5 lines).
- **Risk if removed**: none — no test asserts on them; `SCROLLBAR_CSS`/`style.textContent` paths are
  unaffected.

### F7. `inject.test.js`: two fields of the `setupPage()` return value are never read  [NO-REFS]
- **Location**: `android-shell/tools/inject.test.js:417` (`configValue`, `window`)
- **Symbol**: `page.configValue`, `page.window`
- **Why dead**: `setupPage()` returns `{document, window, posts, protocol, configValue, dom, mediaQueries,
  teardown}`. Every field except `configValue` and `window` is destructured or accessed by tests. Tests
  that need the window use the global (`globalThis.window`, e.g. `:541`, `:1655`), never `page.window`.
  `configValue` is not itself dead — it is the object the fake `ZCodeShell.config()` serves
  (`:284`) — only the *export* is unread.
- **Reference search**: `configValue` over `android-shell` → 5 hits, all in `inject.test.js`
  (decl 263, use 284, export 417); `page\.window` → 1 hit (the export at 417). No other reader.
- **Confidence**: high.
- **Suggested action**: drop the two keys from the return object (or keep `configValue` if a future test
  is expected to flip `passiveObserve`).
- **Risk if removed**: none.

### F8. `TaskStore.hasRunningTasks` — production property whose only readers are tests  [TEST-ONLY]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/TaskStore.kt:144-146`
- **Symbol**: `TaskStore.hasRunningTasks` (`@get:Synchronized val … get() = workspaces.values.any { runningIn(it).isNotEmpty() }`)
- **Why dead**: the only readers in the whole repository are the two assertions in `NotifyStateTest`.
  Its near-neighbours are all consumed by production: `runningTaskRefs()` (`ShellRuntime.kt:326/403/461/
  821/933/1533`, `WebAppBridge.kt:50`), `workspaceCount()` (`SettingsActivity.kt:236`),
  `workspaces()`/`Update.*` (see F-notes). So this is a purpose-built test convenience that leaked into
  the production class.
- **Reference search**: `\bhasRunningTasks\b` over `E:\Zcode_harmony` → **3 hits**: `TaskStore.kt:145`
  (declaration), `NotifyStateTest.kt:464`, `NotifyStateTest.kt:486` (readers). Checked the JS-bridge path
  too: the only `addJavascriptInterface` object is `WebAppBridge`, and no JS asset contains the string
  `hasRunningTasks`.
- **Confidence**: high.
- **Suggested action**: delete the property and rewrite the two assertions on `runningTaskRefs().size`
  (which is what the tests actually mean and is production-used).
- **Risk if removed**: the two NotifyStateTest assertions must be rewritten in the same change; nothing
  else compiles against it.

### F9. `PreviewSettle.delayFor`'s two injectable knobs are supplied by no caller, test or production  [NO-REFS]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/PreviewSettle.kt:33-34`
- **Symbol**: parameters `settleMs: Long = SETTLE_MS`, `maxWaitMs: Long = MAX_WAIT_MS`
- **Why dead**: they exist so a caller can substitute the two constants — and no caller ever does.
  Production passes two arguments (`ShellRuntime.kt:383`) and all seven test calls pass two arguments.
  The "testable knob" is therefore never exercised by the very tests it was added for.
- **Reference search**: `delayFor|settleMs|maxWaitMs` over `android-shell` → **13 hits**: 7 in
  `PreviewSettleTest.kt` (all 2-arg), 1 in `ShellRuntime.kt:383` (2-arg), 5 in the declaration itself.
  No named-argument use of `settleMs`/`maxWaitMs` anywhere.
- **Confidence**: high.
- **Suggested action**: inline the two constants into the body and delete the parameters
  (keep the constants public — tests and `ShellRuntime` read them).
- **Risk if removed**: none; if someone later wants a different window they can re-add a parameter.
  (Contrast: `PromotionPolicy.choose(maxFinished=…)` has the same shape but *is* exercised by
  `PromotionPolicyTest.kt:124`, so it is not a finding.)

### F10. `NotifyStateTest`: "there is one id per task, live or finished" tests neither claim  [VESTIGIAL]
- **Location**: `android-shell/app/src/test/java/com/zcode/remote/core/NotifyStateTest.kt:338-345`
- **Symbol**: `@Test fun \`there is one id per task, live or finished\``
- **Why dead**: the comment states the invariant — "完成不再是'另开一条记录'…同一条记录原地改状态 ⇒
  id 只有一个来源" — but the body only recomputes `notificationIdFor("ws-1", "s-1")` and range-checks it.
  It never builds a finished-card id and never compares a live id with a completed id, so the named
  invariant cannot fail this test. Lines 342-343 are a strict duplicate of `:331`/`:335` from the
  preceding test (`notification ids are stable and workspace scoped`); the only new information is the
  upper bound `ONGOING_ID_BASE + ONGOING_ID_RANGE`.
- **Reference search**: read all 33 `@Test` bodies in the file; the *real* one-record check lives in
  `NotifyStateTest.kt:384-387` (`assertEquals(first.id, notificationIdFor(next.completed[0].workspaceKey,
  …))`), which is why the standalone test at 338 adds nothing new.
- **Confidence**: medium-high (judgement about test value, not about a symbol's readers).
- **Suggested action**: fold the upper-bound assertion into the `:330` test and delete the 338–345 test,
  or strengthen it to compare `notificationIdFor(live)` with the id of the emitted completion event.
- **Risk if removed**: none; the store-level test at `:384` already pins the one-id invariant.

### F11. Stale test-count claims in README and docs/18 §6  [VESTIGIAL]
- **Location**: `android-shell/README.md:425` ("现共 **88 项**");
  `android-shell/docs/18-全仓审计-错误路线残留与清理.md:740-741` ("94 项全绿", "Kotlin 单测 92 项")
- **Symbol**: n/a (documentation rot)
- **Why dead**: the numbers are contradicted by the code and by the same documents elsewhere.
- **Reference search / measurement** (all executed, not inferred):
  - Kotlin: `@Test` grep over `app/src/test/**` → **111** annotations; file count → **11** test classes.
    Per class: NotifyStateTest 33, RelayWireTest 21, ControllerTasksStateTest 12, PromotionPolicyTest 8,
    UploadMimeTest 8, CarrierHandbackTest 7, PageBarColorTest 6, SurvivalVerdictTest 5, RelayBridgeTest 5,
    PreviewSettleTest 4, Tier2ProofTest 2 = 111. → `README.md:247` ("Kotlin **111 项 / 11 个测试类**"
    plus that exact per-class breakdown) is **correct**; `docs/18:741` ("92 项") is **stale**.
  - JS: `node tools/protocol.test.js` → `tests 52 / pass 52`; `node tools/inject.test.js` →
    `tests 45 / pass 45` → **97**, matching the 52 `test(` / 45 `test(` I counted statically.
    (`node --test tools/*.test.js` cannot run in this sandbox: the runner spawns children with piped
    stdio and dies with `spawn EPERM`; running each file directly is the supported in-process path and
    reports the same totals.) → `README.md:247/719/921` ("97 项") is **correct**;
    `README.md:425` ("88 项") and `docs/18:740` ("94 项") are **stale**.
  - `docs/18:705` (§3.11, later in the same document) already carries the current "JS **97/97** ·
    Kotlin **111/111**", so §6 (line 735-742) contradicts §3.11 *within the same file*.
- **Confidence**: high.
- **Suggested action**: fix the two stale occurrences (or mark §6 as "start-of-round numbers").
- **Risk if removed**: none. README's own rule ("改测试后请同步这一行") is what makes this a finding.

### F12. `cdp-capture.mjs`'s stated purpose is a route that `docs/18` §7 declares void  [SUPERSEDED]
- **Location**: `android-shell/tools/cdp-capture.mjs:5-9` (docstring premise) and `:112` / `:149`
  (the `/api/remote-control/` + `remote-control` WS filters)
- **Symbol**: the script's `window 控制面` objective
- **Why dead**: the docstring says the capture exists because "原生承载要补 window 控制面（docs/16 §8.3
  的五步）". `docs/18` §7 line 751 states the opposite conclusion for that exact route:
  "**`docs/15` §4『必须补 window 控制面』仍然作废**（真凶是 controller 流那次 `rpc:listen`）".
  The script's filters are aimed precisely at that discredited surface — `/api/remote-control/` HTTP and
  the `/ws/remote-control/window/<token>` socket (`docs/15:29/90`) — while the value the project still
  credits it with (see "Suspected") is a different, relay-side sequence.
- **Reference search**: repo-wide `check_kotlin_structure`-style grep for `cdp-capture` → **6 hits**:
  `cdp-capture.mjs:3/15/16` (self), `README.md:484`, `Tier2Probe.kt:400` (both as a *citation* of past
  captures). Nothing invokes it: `.github/workflows/android-shell.yml` never calls it, no `package.json`
  script exists, and no other tool `spawn`s it.
- **Confidence**: medium — the docstring/docs contradiction is proven; whether the tool is still *useful*
  is not (see Suspected U1). It also is not `NO-REFS` at file level: README:484 does point at it.
- **Suggested action**: keep but rewrite the docstring to state the surviving use, or delete with
  `README.md:484` (194 lines + 2 doc lines) if the author confirms it is spent.
- **Risk if removed**: it is the only recorded way to capture the `/api/remote-control/` surface; if that
  surface is ever revisited (error-path work), the tool would have to be rewritten.

### F13. `cdp-capture.mjs`: the documented `--seconds` default is unreachable whenever another flag is present  [UNREACHABLE]
- **Location**: `android-shell/tools/cdp-capture.mjs:23`
- **Symbol**: `const seconds = Number((args[args.indexOf('--seconds') + 1] || 25));`
- **Why dead**: with no `--seconds`, `indexOf` returns `-1` and `args[0]` is used as the value. The
  fallback `|| 25` is therefore reachable only when the argument list is **empty**; the documented form
  `node tools/cdp-capture.mjs --noreload` evaluates `Number('--noreload')` → `NaN`, so the run reports
  "监听 NaNs…" and the listen window collapses to ~0 ms (`setTimeout(r, NaN * 1000)`).
- **Reference search**: read of the whole file (194 lines) — `args` is consumed only at lines 22-24;
  the docstring's usage line `[--seconds N] [--noreload]` (line 15) implies the flags are independent.
- **Confidence**: high on the mechanism (pure argument arithmetic), untested on a device (I did not run
  it: it needs `adb` + a live WebView, both outside this sandbox).
- **Suggested action**: parse with `args.indexOf('--seconds') >= 0 ? Number(args[i+1]) : 25`.
- **Risk if removed**: none (fix only).

### F14. `tools/__pycache__/*.pyc` — stale local build artefacts  [VESTIGIAL, low priority]
- **Location**: `android-shell/tools/__pycache__/check_kotlin_structure.cpython-313.pyc`,
  `.../watch_ci.cpython-313.pyc`
- **Why dead**: byte-code caches from a previous local run; they belong to no build step here (the
  workflow runs `python3 tools/*.py` directly, which recompiles as needed).
- **Reference search**: `git ls-files android-shell/tools` → 11 tracked files, **neither `.pyc` is
  tracked**; `git check-ignore -v …watch_ci.cpython-313.pyc` → matched by
  `android-shell/.gitignore:31 (__pycache__/)` and `:32 (*.pyc)`. So they cannot be committed.
- **Confidence**: high.
- **Suggested action**: delete (housekeeping only, `tools/clean` not needed); explicitly **not** a
  code-debt item.
- **Risk if removed**: none.

---

## Known / intentional (not a new finding)

- `RelayBridge.kt` controller chain + `ControllerTasksState` + `TaskStore.applyLiveTasks` — `docs/18` §4
  keeps them as the protocol-schema/unit-test reference behind `controllerStreamEnabled=false`.
  Re-check: **reason still holds** — `ControllerTasksStateTest` (12 tests) and the controller legs of
  `RelayBridgeTest` are the only executable description of `controller/tasks-index`, and `ShellRuntime`
  still drives `applyLiveTasks` from the page's own controller stream (`ShellRuntime.kt:1616-1631`).
- `RelayWire.METHOD_ROWS_RANGE` — §4 marks it a dead end; no test in this area references it.
  Re-check: reason holds (nothing changed).
- `tier2_test` / `tier2_stop` / `tier2_takeover` — experiment hooks kept for takeover verification; not
  referenced from `tools/` at all. Re-check: reason holds.
- `nudgePageRecovery` + `nudge_now` / `nudge_close` — diagnostic-only; `inject.test.js:1709-1743` pins the
  two `__zcodeShellNudgeRecover` modes as "仅取证". Re-check: reason holds, and the tests state it.
- `SHELL_VISIBILITY_HIJACK=false` — `inject.test.js:525-565` asserts the page keeps its real visibility
  and that lifecycle events are delivered. `docs/18` §2.4's other item (the `inject.test.js` header
  comment that used to contradict that test) is **fixed**: the header now reads "the read-only contract
  (the visibility hijack stays off …)" (lines 11-16), consistent with `:529-565`. Nothing to report.
- Node suites' read-only/passive assertions (`protocol.test.js:392-403`, `inject.test.js:705-785`) — these
  deliberately pin *deleted* D7 behaviour by asserting its absence ("D7 开关已删除：客户端不再有…"), which
  is the intended pattern after a removal, not rot. Explicitly **not** reported as stale tests.

## Suspected but unproven

- **U1. What `cdp-capture.mjs` actually recorded.** `README.md:484-485` and `Tier2Probe.kt:399-400`
  credit it with recording the post-pairing relay frame order
  (`auth_init → auth_challenge → auth_response → auth_ack → bootstrap-request → workspace-bridge-open`).
  The script as it stands only logs `Network.requestWillBeSent` URLs containing `/api/remote-control/`
  and `Network.webSocket*` frames for sockets whose URL contains `remote-control` — i.e. the
  `/ws/remote-control/window/<token>` surface (`docs/15:29/90`), not the relay socket. Missing evidence:
  which socket carried `auth_init` at capture time (needs the original capture log, or a device rerun).
  If the two are different sockets, the citation is stale and the tool's surviving use is nil.
- **U2. `Tier2ProofTest.kt:29`** uses Kotlin's `assert(...)`, not `org.junit.Assert`. It only executes
  because Gradle's `Test` task defaults `enableAssertions = true`; `app/build.gradle.kts:133-134`
  (`testOptions { unitTests.isReturnDefaultValues = true }`) sets nothing either way. If anyone ever
  disables assertions for the test JVM, `differentRolesChangeTheProof` becomes a green no-op test.
  Missing evidence: a Gradle run (not possible in this sandbox). Suggested: switch to
  `assertNotEquals`/`assertTrue` for uniformity.
- **U3. `FakeWebSocket.CLOSING`** (`inject.test.js:233`) is never asserted by a test, unlike `OPEN`/
  `CONNECTING`/`CLOSED` (`:484-486`). It is probably needed for wrapper parity (`inject.js:314`
  `Object.setPrototypeOf(ShellWebSocket, NativeWebSocket)`), but no evidence forces it. Not reported as
  dead because the fake is a faithful stand-in and the constant is 1 line.

## Summary

- Findings: 14 — `NO-REFS` 4 (F2, F6, F7, F9), `VESTIGIAL` 6 (F1, F3, F5, F10, F11, F14),
  `WRITE-ONLY` 1 (F4, two symbols), `TEST-ONLY` 1 (F8), `SUPERSEDED` 1 (F12), `UNREACHABLE` 1 (F13).
  F8 is the area's only `TEST-ONLY` pointer (a production property whose sole readers are two test
  assertions) — nothing else in this area's blast radius is read by tests alone.
- Highest-value deletions:
  1. `tools/doc_snapshot.py:247` + `:488` — `RENDERED_ENTRIES` and `register_rendered`, a 44-line dead
     table + writer pair with zero references (`NO-REFS`).
  2. `app/src/main/java/com/zcode/remote/core/TaskStore.kt:144` — `hasRunningTasks`, production API
     reachable only from two test assertions (`TEST-ONLY`).
  3. `app/src/test/java/com/zcode/remote/core/RelayBridgeTest.kt:469` — the `workspaceId` snapshot key that
     `docs/18` §2.4 claims was deleted and that `SessionsIndexState` never reads (`VESTIGIAL`, same rot
     class as the original `assertTrue(state.ready)` cleanup).
- Real vs claimed counts: Kotlin **111 `@Test` / 11 classes** (README claims 111/11 → correct;
  `docs/18` §6 claims 92 → stale). JS **52 + 45 = 97** (README claims 97 → correct; `README.md:425`
  claims 88 and `docs/18` §6 claims 94 → both stale).
- Clean negatives worth recording: **no** `@Test` body in the 11 Kotlin files is assertion-free (all 111
  scanned), **no** `test()` body in either Node suite is assertion-free (97 scanned), every tool file is
  referenced from `README.md` and/or a test, and the two structural checkers are genuinely wired into CI
  (`\.github/workflows/android-shell.yml:105` and `:112`) while `watch_ci.py` appears there only as a
  comment citation (`:29`) — consistent with how the README describes it (a human-run CI reader).
  `tools/watch_ci.py`, `cdp.mjs`, `device_check.sh`, `notif_dump.py`, `check_resources.py` and
  `check_kotlin_structure.py` contain no dead functions, flags or branches (each file read end to end;
  every declared helper has a call site and every CLI flag is reachable).
- Estimated removable lines: **~62** (F2 44, F5 5, F6 5, F4 4, F10 3, F9 2, F7 2, F1 1) — plus,
  if U1 confirms F12, the whole 194-line `cdp-capture.mjs` and 2 README lines.

---

## Status verification (2026-09-19)

Re-verified by grep over `app/src` + `tools/` only. All four cleanup batches landed in commit `b627610` (2026-09-18); batch markers below cite in-code comments (A22/B3 = 第 2 批 A/B 级条目) where present. 注：清理后测试基线本身变了——现为 JS 82（protocol 37 + inject 45）/ Kotlin 122 项 · 12 类（新增 `InjectionReadinessTest`），下表"测试文件现状"均指当前版本。

| 条目 | 原判定 | 当前状态 | 备注 |
| --- | --- | --- | --- |
| F1 `RelayBridgeTest.kt` / `fake-desktop.js` / `protocol.test.js` 里的 `workspaceId` fixture 键 | VESTIGIAL | **已删除** | 三处写入均无 hits；`RelayWireTest.kt:98` 的黄金字节字面量里的 `workspaceId` 按原判定保留（冻结线格式） |
| F2 `doc_snapshot.py` 的 `RENDERED_ENTRIES` + `register_rendered` | NO-REFS | **已删除** | `doc_snapshot.py` 无任何 hits，44 行成对死码随 b627610 移除 |
| F3 `doc_snapshot.py` docstring 的 `--force 03` 用法 | VESTIGIAL | **已修复** | docstring 现写 `--force --only 03`，与 `argparse`（`--probe/--force/--only`，`:454-456`）一致 |
| F4 `fake-desktop.js` 的 `acked` / `openCount` | WRITE-ONLY | **已删除** | 两字段及其写入均无 hits |
| F5 `fake-desktop.js` 的 `subscribeDelayMs` | VESTIGIAL | **已删除** | 无任何 hits（连同它守门的 setTimeout 分支） |
| F6 `inject.test.js` 的 `hasAttribute` / `isConnected` / `protocols` | NO-REFS | **仍在（仍死）** | 三者仍在 `inject.test.js:99/:72/:210`；`inject.js` 仍只读自己的构造参数（`:307-310`）与 `getAttribute`，不碰这三个 fake 成员 |
| F7 `setupPage()` 返回值里的 `configValue` / `window` | NO-REFS | **仍在（仍死）** | `inject.test.js:417` 仍导出两键；`page.window` 仍无读点，`configValue` 仍只自产自销（`:263/:284`） |
| F8 `TaskStore.hasRunningTasks` | TEST-ONLY | **已删除** | 与 core-state F4 同一条：`TaskStore.kt:198-200` KDoc 记录删除（第 2 批 B3），断言改走 `runningTaskRefs()` |
| F9 `PreviewSettle.delayFor` 的 `settleMs`/`maxWaitMs` 参数 | NO-REFS | **已删除** | 签名现为 `delayFor(lastPublishAt, now)`（`PreviewSettle.kt:31-34`），两个常量已内联进函数体；`PreviewSettleTest` 亦无 `settleMs`/`maxWaitMs` hits |
| F10 `NotifyStateTest`「there is one id per task, live or finished」测试 | VESTIGIAL | **已删除（按建议折叠）** | 测试已不存在；`NotifyStateTest.kt:355` 标注「A22（2026-09-18）：这条上界原先只钉在一条叫…的测试里」——上界断言折进了前一条 id 稳定性测试 |
| F11 README/docs/18 的陈旧测试计数 | VESTIGIAL | **已修复** | `README.md:425` 现为「现共 **82 项**」，`README.md:59/247` 为「JS 82 + Kotlin 122（2026-09-18 全仓清理后实测）」，与实测一致；docs/18 §6（`:784-790`）订正为该轮自己的 97/111（历史记录口径），原「94/92」已不存在 |
| F12 `cdp-capture.mjs` 的"window 控制面"前提 | SUPERSEDED | **仍在（未动）** | 文件与 docstring（`:5-9`，仍引 docs/16 §8.3 五步、`/api/remote-control/` 面）原样保留，README 仍指向它 |
| F13 `cdp-capture.mjs` 的 `--seconds` 解析 | UNREACHABLE | **仍在（未修）** | `cdp-capture.mjs:23` 仍是 `Number((args[args.indexOf('--seconds') + 1] || 25))`，与 `--noreload` 同用时默认 25s 仍不可达 |
| F14 `tools/__pycache__/*.pyc` | VESTIGIAL | **仍在（仍死）** | 目录还在且又多了一个（`check_kotlin_structure`/`check_resources`/`doc_snapshot` 三个 `.pyc`）；仍未被 git 跟踪，纯本地 housekeeping |
