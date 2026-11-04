> ⚠️ Historical audit baseline (2026-09-18, pre-cleanup). Status as of 2026-09-19: see "Status verification" section at the end. Many items listed as dead have since been deleted in batches 1–4 (commit b627610).

# core-state — dead-code findings

## Scope audited
- `android-shell/app/src/main/java/com/zcode/remote/core/TaskStore.kt` (502 lines) — task list per workspace; three-source phase overlay; notification decisions (`Update` / `RunningNotification`).
- `android-shell/app/src/main/java/com/zcode/remote/core/ControllerTasksState.kt` (223 lines) — `controller/tasks-index` state machine (snapshot / deltas / fragment assembly / resync flag).
- `android-shell/app/src/main/java/com/zcode/remote/core/SessionsIndexState.kt` (220 lines) — `sessions-index` state machine + `SessionEntry` wire projection for `ShellRuntime.onSessions`.
- `android-shell/app/src/main/java/com/zcode/remote/core/Prefs.kt` (79 lines) — SharedPreferences wrapper (4 accessors).
- `android-shell/app/src/main/java/com/zcode/remote/core/PageBarColor.kt` (99 lines) — state/theme token → status-bar colour table.
- `android-shell/app/src/main/java/com/zcode/remote/core/RemoteUrl.kt` (89 lines) — remote URL validation / display / extraction / origin rule.
- `android-shell/app/src/main/java/com/zcode/remote/core/UploadMime.kt` (69 lines) — WebView accept-list → SAF MIME filter.
- `android-shell/app/src/main/java/com/zcode/remote/core/WindowInsets.kt` (86 lines) — edge-to-edge + status-bar/IME inset handling.

Reference searches covered the whole repo (`android-shell/**`: Kotlin main + `app/src/test`, `app/src/main/assets/*.js`, `tools/*.js|*.py`, `README.md`, `docs/18-*.md`), not just the eight files.

## Findings

### F1. `TaskStore.Workspace.path` and `.identity` are written on every update and read by nobody  [WRITE-ONLY]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/TaskStore.kt:19` and `:20` (declarations), parameters `:208` / `:209`, assignments `:225` / `:226` (`applyWorkspace`) and `:270` / `:271` (controller-newcomer branch).
- **Symbol**: `TaskStore.Workspace.path`, `TaskStore.Workspace.identity`
- **Why dead**: Both fields are fed from the `sessions-index` payload (`ShellRuntime.kt:1660-1661` → `data.optString("workspacePath")` / `"workspaceIdentity"`), carried through `workspace.copy(...)` and the `previous?.x` fallbacks, and then never read by any consumer: the only production consumer of `TaskStore.workspaces()` is `WebAppBridge.config()` (`WebAppBridge.kt:45-49`), which reads `workspace.key` only. `workspace.title` and `workspace.tasks` are read inside `TaskStore`; `path`/`identity` are not.
- **Reference search**: `\.path\b|\.identity\b` over `android-shell/**/*.kt` → 4 hits: `RemoteUrl.kt:43,58` (`uri.path`, a local `val` on `android.net.Uri`, unrelated) and `TaskStore.kt:225,226` (the class's own previous-value fallback — a read that only feeds the same never-read field). Also searched `Workspace\b` (47 hits: all construction/copy/`key`/`title`/`tasks` sites), `component[1-9]\(` → no matches (no destructuring), and `workspaces\(\)` → 2 hits (`TaskStore.kt:139` declaration, `WebAppBridge.kt:45` reader of `.key`).
- **Confidence**: high (for "no reader"); the only judgement call is whether the field is deliberately kept as wire-shape parity.
- **Suggested action**: delete — together with the two `applyWorkspace` parameters and the `previous?.path` / `previous?.identity` fallbacks — **or**, if shape-parity is the reason to keep them, annotate them the way `docs/18` §4 annotates `Workspace.source`.
- **Risk if removed**: nothing breaks semantically. Call sites must be updated in the same change: `ShellRuntime.kt:1660-1661` (named args), and the named args in `ControllerTasksStateTest.kt:144-145,183-184,223,235,252,264,280,287`, `NotifyStateTest.kt:353,369,397,402,410,423,438,450,460,461,471,472,482,492,493` (they pass `path = …` / `identity = …` positionally/named, so they would stop compiling). Note `docs/18` §4 kept `Workspace.source` for exactly this shape-parity reason but did **not** list `path`/`identity`; treat the two as "decide and annotate" rather than mechanical deletion.

### F2. `SessionsIndexState.SessionEntry.parentSessionId` is produced from the wire and serialised, but no consumer reads it  [WRITE-ONLY]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/SessionsIndexState.kt:16` (declaration), `:26` (`toJson` write), `:46` (`fromRaw` assignment).
- **Symbol**: `SessionEntry.parentSessionId`
- **Why dead**: `SessionEntry` has exactly one consumer contract — `buildUpdate()` → `BridgeSession.onSessionsUpdate` (`RelayBridge.kt:249`) → `ShellRuntime.onSessions` (`ShellRuntime.kt:1634-1666`). That consumer reads `sessionId`, `title`, `phase`, `preview`, `pendingInteractionId`, `lastActivityAt`, `hasBackgroundWork` — i.e. the other 7 of the 8 fields — and never touches `parentSessionId`. The class KDoc (`SessionsIndexState.kt:11-12`) states the output shape is "field-for-field identical with `ShellRuntime.onSessions`'s consumption shape"; `parentSessionId` is the one field that violates that claim. This is the same class of defect as the already-deleted `workspaceId` / `ready` (`docs/18` §2.1).
- **Reference search**: `parentSessionId` over all of `android-shell/**` → 5 hits: `SessionsIndexState.kt:16,26,46` (declaration + write + assignment), and `zcode-protocol.js:804-805` (the **JS** `normalizeSession` building its own, separate `SessionEntry` — not a reader of the Kotlin object). No hit in `ShellRuntime.kt`, `RelayBridge.kt`, `NativeRelayClient.kt`, either test file, `README.md` or `docs/**`.
- **Confidence**: high.
- **Suggested action**: delete (3 lines). Do not touch the JS-side field — that is a different implementation in another agent's area.
- **Risk if removed**: no test pins it (`RelayBridgeTest.kt:34-42` asserts `key`, `title`, `source`, `sessionId`, `phase` only). If the JS `SessionEntry` is ever meant to be mirrored exactly, keep it and add the annotation instead — but nothing reads it today.

### F3. `TaskStore.removeWorkspace()` is only called from a unit test  [TEST-ONLY]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/TaskStore.kt:420-435` (16 lines, plus the KDoc at `:419`).
- **Symbol**: `TaskStore.removeWorkspace(key: String): Update`
- **Why dead**: no production path ever removes a single workspace. The only production teardown is `store.reset()` (`ShellRuntime.kt:1866`), which drops everything. The KDoc's stated use case ("when its bridge is gone for good") has no caller: neither `RelayBridge`'s bridge-close/`onBridgeFailed` paths nor `NativeRelayClient` call it.
- **Reference search**: `removeWorkspace` over all of `android-shell/**` → 2 hits: `TaskStore.kt:421` (declaration) and `NotifyStateTest.kt:473` (`store.removeWorkspace("ws-1")`, the only caller). No JS/asset/doc hit.
- **Confidence**: high (no production caller). Whether that is a bug rather than dead code is a product question — a workspace that disappears from the page stays in the store until `reset()`.
- **Suggested action**: investigate further — either delete it with its test, or wire it to the bridge-teardown path (that would be a behaviour change, not a cleanup; out of scope for this audit).
- **Risk if removed**: `NotifyStateTest.kt:468-477` ("removing a workspace cancels its running notifications") loses its subject and would have to be deleted with it. Documented decision? No — `docs/18` never mentions it.

### F4. `TaskStore.hasRunningTasks` is asserted only by tests  [TEST-ONLY]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/TaskStore.kt:144-146`.
- **Symbol**: `TaskStore.hasRunningTasks` (`@get:Synchronized val`)
- **Why dead**: no production reader. The store exposes `runningTaskRefs()` and `runningNotifications()` for the same question, and those are the ones ShellRuntime/WebAppBridge use (`ShellRuntime.kt:326,403,461,821,829,933,1823`, `WebAppBridge.kt:50`).
- **Reference search**: `hasRunningTasks` over all of `android-shell/**` → 3 hits: `TaskStore.kt:145` (declaration) and `NotifyStateTest.kt:464,486` (`assertTrue(store.hasRunningTasks)` / `assertFalse(...)`). Nothing in `main/`.
- **Confidence**: high. (Its predecessor `NotifyUpdate.hasRunning` was deleted in `docs/18` §2.1 for the same reason — "零读取方".)
- **Suggested action**: delete with its two assertions, or keep if a future guard needs it (annotate).
- **Risk if removed**: only the two test assertions; both can be re-expressed with `runningTaskRefs().isNotEmpty()` without losing coverage.

### F5. `RunningNotification.workspaceKey` and `.workspaceTitle` are read only by tests  [TEST-ONLY]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/TaskStore.kt:28` (`workspaceKey`) and `:29` (`workspaceTitle`); assignments in `buildRunningList` at `:486` / `:487`.
- **Symbol**: `TaskStore.RunningNotification.workspaceKey`, `TaskStore.RunningNotification.workspaceTitle`
- **Why dead**: every field of `RunningNotification` that production reads is consumed by `Notifier.syncRunningTasks` (`id`, `task`, `title`, `locateTitle`, `body`, `status`, `activityAt` — `Notifier.kt:278,285,289,295,296,297,308`, `PromotionPolicy.kt:59-60`, `Notifier.kt:569-571`). The workspace identity is not needed there because the notification intent carries `EXTRA_SESSION_ID` and `MainActivity` re-resolves the workspace from the live store (`ShellRuntime.kt:403`), and the body text already received the workspace title as a plain string inside `NotifyState.formatBody(..., workspace.title)` (`TaskStore.kt:490-493`).
- **Reference search**: `workspaceTitle` over `android-shell/**` → `TaskStore.kt:29` (decl), `:487` (assignment), `NotifyStateTest.kt:494` (read), `PromotionPolicyTest.kt:34` (construction with a named arg), `SessionsIndexState.kt:191` + two `.js` hits (`workspaceTitle` is a **different**, JS-local helper). `\.workspaceKey` over `main/**/*.kt` → the only `RunningNotification`-typed reads are none: `Notifier.kt:400` is `card.workspaceKey` on the private `FinishedCard` (`Notifier.kt:51-52`), `:214/219/223/371/462` are `event.workspaceKey` on `CompletionEvent`, `ShellRuntime.kt:1622` is `LiveTask.workspaceKey`. Test reads: `NotifyStateTest.kt:463,476`. No `RunningNotification.copy(...)` exists anywhere (only `Workspace.copy`, `TaskSnapshot.copy`, `CompletionEvent.copy`, `FinishedCard.copy`).
- **Confidence**: high.
- **Suggested action**: delete both fields and their two assignments; keep the test's intent by asserting on the store (`workspaceCount()` / `runningTaskRefs()`).
- **Risk if removed**: `NotifyStateTest.kt:463,476,494` and `PromotionPolicyTest.kt:31-41` pass them as named constructor arguments and must be edited in the same change (`PromotionPolicyTest.running()` can simply drop the two lines).

### F6. `ControllerTasksState.LiveTask.liveStatus` is read only by one test assertion  [TEST-ONLY]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/ControllerTasksState.kt:34` (declaration), `:180` (assignment in `liveTasks()`).
- **Symbol**: `ControllerTasksState.LiveTask.liveStatus`
- **Why dead**: the projection's consumers read only `workspaceKey` / `sessionId` / `title` / `phase`: `TaskStore.applyLiveTasks` (`TaskStore.kt:248-262`, `:290-294`) and `ShellRuntime.onPageControllerFrame` (`ShellRuntime.kt:1617-1631`). The raw `liveStatus` string is kept only so the test can prove `liveStatus → phase` mapping happened (`ControllerTasksStateTest.kt:74`); the same test already asserts the mapped `phase` at `:72-73`.
- **Reference search**: `liveStatus` over `android-shell/**/*.kt` → production: `ControllerTasksState.kt:34,173,179,180,216` (all inside the class, incl. the `mapLiveStatus` argument), `TaskStore.kt:89` + `RelayBridge.kt:925,948,952,987,1009` (comments, plus `RelayBridge.ControllerTask.displayStatus/liveStatus` — a **different** class used by the sealed 原生承载 digest path), `ShellRuntime.kt` none. Test: `ControllerTasksStateTest.kt:74` (the one real read), `:207,325` (constructions).
- **Confidence**: high.
- **Suggested action**: delete the field (1 line + 1 assignment) and the `assertEquals("running", live.getValue("sess_a").liveStatus)` assertion at `ControllerTasksStateTest.kt:74`, **or** keep it annotated — see the "Known / intentional" note on this file below, since the file itself is a documented keep.
- **Risk if removed**: one test assertion; the mapping it guards is still asserted through `phase`.

### F7. `UploadMime.normalise`'s default resolver argument is never used  [VESTIGIAL]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/UploadMime.kt:45` — `resolveExtension: (String) -> String? = { null }`.
- **Symbol**: the default value of `normalise`'s second parameter (not the parameter itself — all callers pass a resolver).
- **Why dead**: every call site supplies a resolver, so the default lambda is never evaluated: with it, `normalise` could only ever return `ANY` for dotted/bare tokens. `normalisePlatform` (`:62-65`) passes the `MimeTypeMap` lookup and the 9 test calls pass `lookup`.
- **Reference search**: `UploadMime\.normalise` over all of `android-shell/**` → 12 hits, all with a second argument: `UploadMime.kt:62-64` (`normalisePlatform`) and `UploadMimeTest.kt:17,18,23,30,36,41,47,54,60` (all `normalise(..., lookup)`). No single-argument call anywhere.
- **Confidence**: high (mechanically provable).
- **Suggested action**: keep but annotate, or drop the `= { null }` (making the parameter explicit). Low value — reported only because this file was explicitly in scope for reachability checking.
- **Risk if removed**: none; no caller relies on the default. This is a 0-line cleanup.

### Files with no dead code found
- `Prefs.kt` — all four accessors are read **and** written in production, and the settings-page cross-check changes nothing: `remoteUrl` (read `MainActivity.kt:193,466,487,678,684`, `SettingsActivity.kt:71`, `ShellRuntime.kt:1059`; written `MainActivity.kt:453`), `webViewDebugging` (read `MainActivity.kt:227`; written `ShellRuntime.kt:1218,1231` — reachable via the `wvdebug_on` / `wvdebug_off` adb diag commands), `passiveObserve` (read `WebAppBridge.kt:55` → consumed end-to-end by `inject.js:296,2309` `config().passiveObserve === false`; written `MainActivity.kt:766` via `passive_on`/`passive_off`), `notificationPermissionRequested` (read `MainActivity.kt:645`, written `:649`). **No pref has a UI control, and that is fine** — `SettingsActivity.kt` + `res/layout/activity_settings.xml` contain no switch for any of the four (every row is an action/status row), so the "no UI **and** no consumer" rule never fires. One stale cross-reference worth fixing (not dead code, and in another file): `ShellRuntime.kt:1215` says "设置页里也有同一个开关" for WebView debugging, but `activity_settings.xml` has no such control.
- `PageBarColor.kt` — every public member has a production reader: `stateOf` (`MainActivity.kt:557`), `themeOf` (`:558`), `resolve` (`:570`), `describe` (`:580`), `appearanceLightStatusBars` (`:576,579`), `PageBarState.DEFAULT` (`:77`), `PageBarState.BOOT.token` (`:280`). All three states are producible at runtime: `inject.js:3155-3166` emits `boot` / `main-header` / `main-surface`, so no `when` branch in `resolve` is unreachable.
- `RemoteUrl.kt` — all five members are used from production (`normalize` `MainActivity.kt:534`; `isAllowed` `:257`; `toDisplayString` `:308,454` + `SettingsActivity.kt:72`; `extractFromScannedText` `:117,512`; `originRule` `:427`); both private constants are read inside the object.
- `WindowInsets.kt` — both extension functions are called from two production screens (`MainActivity.kt:161,171`; `SettingsActivity.kt:53,58`); every import is used.
- `TaskStore.kt` / `SessionsIndexState.kt` / `ControllerTasksState.kt` — no dead private helpers, no dead constants, no unreachable `when` branch: `MAX_FRAGMENTS`/`MAX_LOGICAL_FRAGMENTS`, the fragment-assembly path, all three `phaseOverlay` tiers, `applyLivePreview`'s early returns and `forgetStaleLivePreviews` are all reachable from live call sites (`ShellRuntime.kt:375,479,1631,1657,1741,1823,1866`; `RelayBridge.kt:248,251`; `NativeRelayClient.kt:488`). `Update`'s four list fields and `nextFlushAtMs` are all read (`ShellRuntime.kt:452,1716,1720,1728,1736,1761-1763`; `Notifier.kt:213,233,245,253,262,271,275,329`).

## Known / intentional (not a new finding)
- **`TaskStore`'s controller tier + `applyLiveTasks` + `livePhases`/`liveTitles`/`livePhasesAt`** — documented in `docs/18` §4 as "恒空（`livePhases` 永不写入）… kept as the trace of the runtime-fallback design". **Re-check: the stated reason is now stale — but the keep is more necessary than ever.** `docs/18` §3.11 (same day, later) added a third source: the page's *own* `controller/tasks-index` stream is forwarded purely passively (`inject.js:823` `post('controllertasks', wire)` → `ShellRuntime.kt:1529-1538` → `onPageControllerFrame` → `store.applyLiveTasks` at `ShellRuntime.kt:1631`), so `livePhases` **is** written in production and the controller tier of the three-level priority is not permanently empty. What is permanently empty is only the *原生承载/RelayBridge* controller subscription (`RelayBridge.kt:364-377`, `controllerStreamEnabled=false`). Consequence for the cleanup: deleting `applyLiveTasks` or the overlay maps would break the 2026-09-18 third-source fix. Keep; if anything, the `docs/18` §4 row should be corrected.
- **`TaskStore.Workspace.source`** — kept as a wire-shape field (§4). Re-check: reason still holds as a *decision*, and the field is indeed never read (`\.source\b` over `android-shell/**/*.kt` → 0 hits; only the three assignments at `TaskStore.kt:227,272` and the test constructions). Note the branch that writes `"controller"` (`:272`) **is** reachable via the page controller stream above.
- **`ControllerTasksState.kt` as a whole** — §4 keeps it as the protocol-schema + unit-test reference for the `controllerStreamEnabled=false` seal. Re-check: still justified, and the file is in fact **live production code**, not kept-dead code: `ShellRuntime.kt:1611` instantiates a second `ControllerTasksState` (`pageControllerTasks`) for the page stream and its `applyWire`/`liveTasks`/`needsResync` are exercised in production. Only the RelayBridge-side chain (`controllerState.bind/applyWire/liveTasks/logEpoch/seq/resetState`, lines 408-485) sits behind the seal. Any future deletion must therefore keep the class itself.
- **`Prefs.subscribeAllWorkspaces` (the D7 switch)** — §5 keeps the *product decision* pending. Re-check: the accessor no longer exists in code at all; the only remnant is the explanatory KDoc at `Prefs.kt:27-37` (which records that the old `subscribe_all_workspaces` key must never be re-linked) plus the orphaned on-device key. Reason still holds: yes — deleting the KDoc would delete the "never link this back" warning, and the stale key costs nothing.
- Other §5 items (`RelayWire.METHOD_ROWS_RANGE`, `carrier_*`, `nudgePageRecovery`/`nudge_*`, `MODE_SAVE`, the `inject.js` watchdog block, the `zcode-protocol.js` self-subscribe stream) do not intersect this scope.

## Suspected but unproven
- `RemoteUrl.normalize` (`:29-33`), `toDisplayString` (`:52-56`) and `originRule` (`:82-86`) wrap `android.net.Uri.parse` in `try { … } catch (e: Exception) { … }`. If `Uri.parse` cannot throw for a `String`, the three `catch` blocks are unreachable (`UNREACHABLE`). I could not prove the negative offline: `Uri.parse` is an Android framework method (AOSP source not available in this workspace) and on the JVM unit-test classpath the stub *does* throw, which may be exactly why the guards exist. Not reported.
- Non-claims, listed for completeness only (visibility, not dead code): `SessionsIndexState.applyLogical` is `public` (`:128`) while its only callers are inside the class (`:90,124`) — `ControllerTasksState` makes the equivalent function `private`; and `ControllerTasksState.subscriptionId`'s getter (`:43`) is read only inside the class (`:142`). Neither has zero readers, so neither is a finding.

## Summary
- Findings: 7 (WRITE-ONLY 2, TEST-ONLY 4, VESTIGIAL 1) covering 9 symbols.
- Highest-value deletions:
  1. `TaskStore.kt:19,20` — `Workspace.path` / `Workspace.identity`, written on every `sessions-index` frame and on every `copy()`, read by nothing (`WebAppBridge.config()` only needs `.key`).
  2. `SessionsIndexState.kt:16,26,46` — `SessionEntry.parentSessionId`, produced from the wire and serialised into the payload that `ShellRuntime.onSessions` consumes, which ignores the key (the exact analogue of the already-deleted `workspaceId`).
  3. `TaskStore.kt:420-435` — `removeWorkspace()`, 16 lines of store teardown whose only caller is `NotifyStateTest.kt:473`; production only ever calls `store.reset()`.
- Estimated removable lines: ~37 production lines (`path`/`identity` 8, `parentSessionId` 3, `removeWorkspace` 16 + its 1-line KDoc, `hasRunningTasks` 3, `RunningNotification` fields 4, `liveStatus` 2) plus ~12 test-line edits that must land in the same change (named-argument call sites in `ControllerTasksStateTest`, `NotifyStateTest`, `PromotionPolicyTest`).

---

## Status verification (2026-09-19)

Re-verified by grep over `app/src` + `tools/` only. All four cleanup batches landed in commit `b627610` (2026-09-18); batch markers below cite the in-code comments (B1/B3 = 第 2 批 B 级条目).

| 条目 | 原判定 | 当前状态 | 备注 |
| --- | --- | --- | --- |
| F1 `TaskStore.Workspace.path` / `.identity` | WRITE-ONLY | **仍在（仍死）——用户裁决保留** | 两字段仍在（`TaskStore.kt:36-37`），仍无消费读点（仅 `applyWorkspace` 的自保兜底 `:306-307`）。`TaskStore.kt:19-35` 新增整段 KDoc：三件套 `path`/`identity`/`source`「要留一起留、要删一起删」，明示「审计时别按有没有读逐个判」——原建议"删或标注"走了**标注**分支，且三字段合一条约 |
| F2 `SessionEntry.parentSessionId` | WRITE-ONLY | **仍在（仍死）——按建议标注** | 仍在 `SessionsIndexState.kt:21`（声明）/`:31`（toJson）/`:51`（fromRaw），仍无 Kotlin 读点；字段上新增 KDoc「**协议形状字段**……不是'死字段'，是'没人读的协议字段'」 |
| F3 `TaskStore.removeWorkspace()` | TEST-ONLY | **仍在（仍死）** | `TaskStore.kt:777`；全仓唯一调用方仍是 `NotifyStateTest.kt:492` 的 `store.removeWorkspace("ws-1")`，未接 bridge-teardown |
| F4 `TaskStore.hasRunningTasks` | TEST-ONLY | **已删除** | `TaskStore.kt:198-200` 的 `runningTaskRefs()` KDoc 记录「旁边原本还有个 hasRunningTasks……遂删」；`NotifyStateTest.kt:481` 标注「B3：已删」（b627610 第 2 批 B3），两处断言改为 `runningTaskRefs()` 口径 |
| F5 `RunningNotification.workspaceKey` / `.workspaceTitle` | TEST-ONLY | **仍在（仍死）** | 两字段仍在（`TaskStore.kt:43-44`，赋值 `:851-852`）；生产端（`Notifier.syncRunningTasks`）仍不读，读点仍是测试（`NotifyStateTest.kt:480/495/513`）与 `PromotionPolicyTest.kt:33` 的具名构造 |
| F6 `ControllerTasksState.LiveTask.liveStatus` | TEST-ONLY | **仍在（仍死）** | 仍在 `ControllerTasksState.kt:34`（声明）/`:171`（赋值）；生产消费端 `ShellRuntime.onPageControllerFrame`（`ShellRuntime.kt:2038-2054`）与 `TaskStore.applyLiveTasks` 仍只读 `workspaceKey`/`sessionId`/`title`/`phase`，唯一读点仍是 `ControllerTasksStateTest.kt:74`。类本身仍是活的生产代码（页面 controller 流） |
| F7 `UploadMime.normalise` 的默认 resolver | VESTIGIAL | **已删除** | `UploadMime.kt:45` 参数现为必填 `resolveExtension: (String) -> String?`，`= { null }` 默认值已随 b627610 移除 |

Known / intentional 项复核：`Workspace.source` 与 F1 同批获得整段"三件套"标注（仍 0 读点）；`Prefs.subscribeAllWorkspaces` 的 D7 解释性 KDoc 仍在（`Prefs.kt:28-40`，含「永远不要把这条链接回来」警示）；`ControllerTasksState` 仍被 `ShellRuntime.kt:2034` 的 `pageControllerTasks` 实例化驱动页面流——三条保留理由全部维持。
