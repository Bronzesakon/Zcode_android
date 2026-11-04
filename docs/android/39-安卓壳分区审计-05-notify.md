> ⚠️ Historical audit baseline (2026-09-18, pre-cleanup). Status as of 2026-09-19: see "Status verification" section at the end. Many items listed as dead have since been deleted in batches 1–4 (commit b627610).

# notify — dead-code findings

## Scope audited

- `android-shell/app/src/main/java/com/zcode/remote/notify/Notifier.kt` (576 lines) — all notification rendering: 4 channels, live/ongoing cards, in-place finished cards, group summary, orphan-card adoption sweep, transient completion/attention notifications.
- `android-shell/app/src/main/java/com/zcode/remote/notify/LiveUpdate.kt` (109 lines) — Android 16 Live Updates (ColorOS 流体云) promotion extras + eligibility diagnostics.
- `android-shell/app/src/main/java/com/zcode/remote/notify/CardDismissReceiver.kt` (31 lines) — manifest-declared receiver for the 「知道了」 action on a promoted finished card.
- `android-shell/app/src/main/java/com/zcode/remote/core/NotifyState.kt` (329 lines) — pure state machine: phase transitions, 3 s completion observation window, interaction de-dupe, card id derivation, title/body formatting.
- `android-shell/app/src/main/java/com/zcode/remote/core/PromotionPolicy.kt` (70 lines) — pure slot allocation (all running cards; newest ≤3 finished cards).
- `android-shell/app/src/main/java/com/zcode/remote/core/PreviewSettle.kt` (40 lines) — pure settle/max-wait delay for card-body pushes.
- `android-shell/app/src/main/java/com/zcode/remote/core/CarrierHandback.kt` (66 lines) — pure hand-back predicate for the native carrier.

Reference trees searched for every claim below: `android-shell/app/src/main/java`, `android-shell/app/src/test/java`, `android-shell/app/src/main/assets` (`inject.js`, `zcode-protocol.js`), `android-shell/app/src/main/res`, `android-shell/app/src/main/AndroidManifest.xml`, `android-shell/tools`, `android-shell/*.md`, `android-shell/app/build.gradle.kts`, `android-shell/app/proguard-rules.pro`.

Extra evidence obtained (not a build, per §6): the six pure sources in scope plus `TaskStore.kt` / `ControllerTasksState.kt` / `RelayWire.kt` and the five test classes were compiled with the cached `kotlin-compiler-embeddable 2.1.0` against cached `junit 4.13.2` / `org.json 20240303`, then executed with `org.junit.runner.JUnitCore`:

```
OK (64 tests)   # NotifyStateTest 33, ControllerTasksStateTest 12, PromotionPolicyTest 8, CarrierHandbackTest 7, PreviewSettleTest 4
```

So every test file in this area is currently **green**, and claims below about "the test asserts / merely constructs" were verified by running them.

---

## Findings

### F1. `NotifyUpdate.running` is a write-only field — every producer fills it with `emptyList()` and nothing ever reads it  [WRITE-ONLY]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/NotifyState.kt:72` (declaration), producers at `TaskStore.kt:186`, `TaskStore.kt:434`, `TaskStore.kt:393`
- **Symbol**: `com.zcode.remote.core.NotifyUpdate.running`
- **Why dead**: it is the first positional parameter of `NotifyUpdate` and all three production construction sites pass `emptyList()`; the only producer that passes a real value (`NotifyState.apply`, `NotifyState.kt:199`) writes it into an object that `TaskStore.recompute` immediately decomposes and rebuilds without touching it. The running list the shell publishes comes from `TaskStore.buildRunningList()`, never from `NotifyUpdate`. This is exactly the `NotifyUpdate.hasRunning` pattern the previous audit removed — the field survived under a new name, and the `running` slot is now a permanent empty-list placeholder.
- **Reference search**: `\bNotifyUpdate\.running\b|\bnotify\.running\b|^\s*running = running,` and `NotifyUpdate\(` over `android-shell/app` + `android-shell/tools` → 4 hits: `NotifyState.kt:72` (declaration), `NotifyState.kt:199` (the one real writer), `TaskStore.kt:393` (`NotifyUpdate(emptyList(), completed, attention)`), `TaskStore.kt:186` and `TaskStore.kt:434` (`NotifyUpdate(running = emptyList(), …)`). **Zero read sites**: the only places a `NotifyUpdate` is consumed are `TaskStore.kt:378` (`notify.heldRunning`), `TaskStore.kt:379` (`notify.completed`), `TaskStore.kt:387` (`notify.attention`) and `TaskStore.kt:464-465` (`notify.completed` / `notify.attention`). `NotifyStateTest` never constructs `NotifyUpdate` directly (it only reads `.running` off the `TaskStore.Update` the same-named field of a *different* class, e.g. `NotifyStateTest.kt:59`).
- **Confidence**: high
- **Suggested action**: delete the field and turn the three `emptyList()` call sites into named-argument construction (they are positional today, so the other three arguments need names or the field order preserved).
- **Risk if removed**: no test pins `NotifyUpdate.running`; the 64 tests above are the whole safety net and none of them reads it. Behaviour identical.

### F2. `TaskSnapshot.hasBackgroundWork` is parsed off the wire, carried through the store, and never read [WRITE-ONLY]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/NotifyState.kt:17` (declaration); writers `ShellRuntime.kt:1652`, `TaskStore.kt:299`; test helpers `NotifyStateTest.kt:33`, `PromotionPolicyTest.kt:29`, `ControllerTasksStateTest.kt:155/194/311`
- **Symbol**: `com.zcode.remote.core.TaskSnapshot.hasBackgroundWork`
- **Why dead**: the flag exists in the JS wire shape and the injected layer is fed from it (`SessionsIndexState.kt:31`, `zcode-protocol.js:811`), but no Kotlin code ever *reads* the `TaskSnapshot` copy: not `NotifyState`, not `TaskStore`, not `Notifier`, not `ShellRuntime`, not `SessionsIndexState`. It is one of the two flags JS itself uses to decide whether a task keeps its card (`zcode-protocol.js:1836`, `:2064`) — that decision is made page-side on the page's own session objects, not on this field.
- **Reference search**: `hasBackgroundWork` over `android-shell/**` (all file types) → 16 hits, partitioned exhaustively: declaration `NotifyState.kt:17`; writers `ShellRuntime.kt:1652`, `TaskStore.kt:299`; test-helper constructions `NotifyStateTest.kt:33`, `PromotionPolicyTest.kt:29`, `ControllerTasksStateTest.kt:155/194/311`, `RelayBridgeTest.kt:416`; a *different* class's independent declaration `SessionsIndexState.kt:21/31/55`; and JS usages `zcode-protocol.js:811/1797/1836/2064`. There is **no read of `TaskSnapshot.hasBackgroundWork` anywhere** (no `task.hasBackgroundWork`, no pattern match, no destructuring that captures it).
- **Confidence**: high
- **Suggested action**: keep but annotate **or** delete with the parameter (deleting touches 1 production writer and 5 test helper calls — mechanical). Deletion is safe; keeping it is also defensible as wire-shape fidelity, matching the already-documented decision for `TaskStore.Workspace.source` (`docs/18` §4).
- **Risk if removed**: nothing reads it, so nothing breaks. If the intent is "shape field isomorphic with the wire format", annotate it the same way `Workspace.source` is annotated rather than leaving it silently unread.

### F3. `AttentionEvent.interactionId` is populated for every attention event and read only by the test  [TEST-ONLY]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/NotifyState.kt:64` (declaration), written at `NotifyState.kt:152`
- **Symbol**: `com.zcode.remote.core.AttentionEvent.interactionId`
- **Why dead in production**: `Notifier.notifyAttention(event)` (`Notifier.kt:451`) reads only `event.workspaceKey` and `event.task` — the notification title is a constant (`R.string.notification_title_attention`) and the id comes from `nextId()`, so the id never reaches a notification, a PendingIntent or a log. De-duplication is done on `NotifyState.notifiedInteractions` (`NotifyState.kt:151`), keyed off `TaskSnapshot.pendingInteractionId`, not off this copy on the event.
- **Reference search**: `interactionId` over `android-shell/**` → 20 hits; the only reads of `AttentionEvent.interactionId` are `NotifyStateTest.kt:220` and `:227` (`first.attention.map { it.interactionId }`), both real assertions. Every other hit is either the JS wire key (`zcode-protocol.js:796-799/812`), `SessionsIndexState.kt:37-40/56`, `TaskSnapshot.pendingInteractionId` (`NotifyState.kt:16/22/150`), or the test helper parameter. Production readers of `AttentionEvent`: `ShellRuntime.kt:1720-1722` (uses `event.task.displayTitle`) and `Notifier.kt:451-471` (uses `event.workspaceKey`, `event.task`).
- **Confidence**: high
- **Suggested action**: delete the field with the `AttentionEvent.workspaceKey, task, interactionId` → `AttentionEvent(workspaceKey, task)` constructor change; the two test assertions move to reading `first.attention.size` / `TaskSnapshot.pendingInteractionId`. If the field is wanted as the "why did this fire" record, annotate it instead.
- **Risk if removed**: `NotifyStateTest.kt:220/227` assert on it, so those two lines must be rewritten in the same change; the behaviour they cover (announce once per interaction) is additionally pinned by `:219-227`'s size/emptiness assertions and by `Notifier` never using it. The KDoc at `NotifyState.kt:95-96` ("announced once per interactionId") stays true because that logic reads `task.pendingInteractionId`.

### F4. `Notifier.taskIntent()`'s `workspaceKey` parameter is never used [VESTIGIAL]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/notify/Notifier.kt:482` (parameter), call sites `Notifier.kt:371`, `Notifier.kt:400`, `Notifier.kt:462`
- **Symbol**: `com.zcode.remote.notify.Notifier.taskIntent(workspaceKey, sessionId, title, index)`
- **Why dead**: the body (`Notifier.kt:487-497`) builds the intent from `sessionId`, `title` and `index` only — the `PendingIntent` request code is the caller-supplied `index`, and `ACTION_LOCATE_TASK` / `EXTRA_SESSION_ID` / `EXTRA_TASK_TITLE` are the only payload. `workspaceKey` was presumably the old request-code basis before the notification id became `NotifyState.notificationIdFor(workspaceKey, sessionId)`.
- **Reference search**: `workspaceKey` inside `Notifier.kt` → 8 hits: `Notifier.kt:52` (`FinishedCard.workspaceKey`, read at `:400`), `:214`, `:219`, `:223` (`event.workspaceKey`, all reads), and `:371`, `:400`, `:462` (the three call sites passing it), `:482` (the parameter). Lines 371/400/462 are the only uses of the parameter, and a call-site pass is not a read — within `taskIntent`'s body (487-498) the identifier never appears again.
- **Confidence**: high
- **Suggested action**: delete the parameter and drop the argument at the three call sites.
- **Risk if removed**: none — `workspaceKey` never influences the request code or the intent extras, and no test covers `Notifier` at all (there is no `Notifier`/`LiveUpdate`/`CardDismissReceiver` reference under `app/src/test`).

### F5. `NotifyUpdate.heldRunning`'s default value is unreachable state, not a default [VESTIGIAL]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/NotifyState.kt:81`
- **Symbol**: `NotifyUpdate.heldRunning = emptySet()`
- **Why dead**: the field itself is alive (`TaskStore.kt:378` reads it), and the only construction that matters passes it explicitly (`NotifyState.kt:202`). The `= emptySet()` default exists for the three convenience constructions that omit it (`TaskStore.kt:186`, `TaskStore.kt:393`, `TaskStore.kt:434`) — and all three of those return an `Update` whose `heldRunning` slot is never read, because `buildUpdate` copies only `completed` / `attention` / `running` / `removedIds` into it (`TaskStore.kt:456-468`). So the default can only ever produce a value no consumer observes; on the one path where the value is observed it is always supplied.
- **Reference search**: `heldRunning` over `android-shell/**` → 13 hits: `NotifyState.kt:81` (this default), `NotifyState.kt:202` (explicit write), `TaskStore.kt:79/160/378/389/390/425/445` (that class's own `heldRunning` set — a different symbol), and `NotifyStateTest.kt:92/99/115/118` (real assertions on the `apply()` path). No caller of the three defaulted constructions reads the field.
- **Confidence**: medium (the classification rests on the three call sites never reading their result — verified — rather than on a compiler warning)
- **Suggested action**: delete with F1 (both are `NotifyUpdate` shape cleanup and touch the same three construction sites).
- **Risk if removed**: none; if F1 is done first, the field becomes required and the default disappears naturally.

---

## Known / intentional (not a new finding)

- `Notifier.adoptOrphanFinishedCards()` (`Notifier.kt:124-160`) and its `EXTRA_FINISHED_CARD` / `"zcode.finishedCard"` marker (`Notifier.kt:137/426/558`) — documented as a deliberate, user-approved degradation: README「已知边界」line 867 `留住的完成卡不跨进程重启（用户拍板"退化无所谓"）：重启后由 adoptOrphanFinishedCards 认领成普通可滑除通知，但不再记得它的任务`. Re-check: reason still holds **yes** — this is the only mechanism that can release a promotion slot held by an `ongoing` card left over from a dead process, and `adoptOrphanFinishedCards` is reached on every start (`ShellRuntime.init` → `Notifier.ensureChannels`, `ShellRuntime.kt:1462`; also `KeepAliveService.kt:32`). Worth noting for the record: the sweep intentionally leaves `finishedCards` empty, so `syncRunningTasks` (`Notifier.kt:318-324`) will never re-promote those cards — that is the documented "不再记得它的任务", not a defect.
- `CardDismissReceiver` as a whole (`CardDismissReceiver.kt:19-30`, manifest `AndroidManifest.xml:116-118`) — alive by contract (OS-instantiated, targeted by `Notifier.dismissIntent()` at `Notifier.kt:429-440`). Both of its constants are genuinely consumed: `ACTION_DISMISS` is compared by intent action (`Notifier.kt:431`) and `EXTRA_ID` is put in the same intent (`Notifier.kt:432`) and read back (`CardDismissReceiver.kt:22`). No dead branches: `id < 0` is reachable when the extra is missing, which is exactly the guard's purpose. **No finding here.**
- `CompletionEvent.failed` (`NotifyState.kt:56`, read at `Notifier.kt:358`) — **not** a dead store; do not re-derive this one as "always false". The pending-completion branch at `NotifyState.kt:171-178` really does set `failed = now in FAILED_PHASES` for a task that was held through the observation window, which is the normal path for a user interrupt: `ControllerTasksStateTest.kt:300` asserts `done.completed[0].failed == true` and passes in the run above. The `R.string.notification_title_failed` branch is therefore live.
- `LiveUpdate`'s reflective `canPostPromotedNotifications` lookup (`LiveUpdate.kt:72`) and the hand-written extras `android.requestPromotedOngoing` / `android.shortCriticalText` (`LiveUpdate.kt:58-59`) — documented in `docs/18` / README line 629 as the deliberate alternative to androidx.core 1.17.0; the README instruction is "toolchain 升到 compileSdk 36 后应换回官方 API". Still `compileSdk = 35` (`app/build.gradle.kts:32`), so the reflective path is required today. Reason holds **yes**.
- The four notification channel ids (`Notifier.kt:540-543`) and `GROUP_RUNNING` (`:545`) — matched at runtime by the platform, not by name in Kotlin. Every one is written and read in this file (`createNotificationChannels` at `:106`, `getNotificationChannel(CHANNEL_RUNNING)` at `:267`, `setGroup(GROUP_RUNNING)` at `:300/330`), and the same literals appear in `README.md:585-596` / `CHANGELOG.md:18`. Searched `running_tasks|task_attention|task_completed|keepalive` over the whole repo → 30 hits, **none** in `assets/*.js` (the injected layer builds no channel id, action string or notification extra). Nothing dead.

---

## Suspected but unproven

- `NotifyState.notificationIdFor` id reuse against a surviving card — `ONGOING_ID_BASE + hash % ONGOING_ID_RANGE` (`NotifyState.kt:312-315`) can collide with an entry still present in `Notifier.finishedCards` (which survives a page reload, because `ShellRuntime.onPageReloaded` → `store.reset()` → `syncRunningTasks` with all-empty lists never clears that map; `Notifier.clearAll()` at `:473-477` does). Missing evidence: whether such a collision is practically reachable — it needs a page reload **and** a later task landing on a colliding id (a 1-in-800 000 event per pair), and by design `syncRunningTasks` removes the entry as soon as the task starts running (`Notifier.kt:245`), so every path I could construct self-corrects. Needs a device/dump experiment, not a code search.
- `LiveUpdate.MIN_API` (`LiveUpdate.kt:56`), `Notifier.CHANNEL_RUNNING/ATTENTION/COMPLETED/KEEPALIVE/GROUP_RUNNING` (`Notifier.kt:540-545`), `Notifier.ID_SERVICE/ID_GROUP_SUMMARY` (`:548/:551`) — public API surface whose readers are either in the same file or in one other production file. Missing evidence: these are *public declarations with no external reader* rather than dead code (`MIN_API`'s readers are all inside its own `object`; `CHANNEL_KEEPALIVE` is a public const in a public class whose only reader is `Notifier.kt:178`), and Kotlin's same-file visibility makes "public" carry no information here. No tool in this sandbox can prove nothing else in the module reads them, so I am not claiming `NO-REFS`. If they are meant to be private, that is a style cleanup, not a deletion.
- `PreviewSettle.delayFor(settleMs, maxWaitMs)` (`PreviewSettle.kt:33-34`) and `PromotionPolicy.choose(maxFinished)` (`PromotionPolicy.kt:55`) — both parameters exist **only** so the unit tests can vary them; production always takes the default (`ShellRuntime.kt:383`; `Notifier.kt:252-255`). Missing evidence: these are the sanctioned "pure function + injection point" pattern the project's own KDoc advertises, and removing them would make the 250 ms / 900 ms / 3-card rules untestable at the boundary (`PreviewSettleTest.kt:17-39`, `PromotionPolicyTest.kt:120-127`). I judge them intentional and list them only so the category question is answered explicitly — they are `TEST-ONLY` parameters that the tests genuinely exercise.

---

## Summary

- Findings: 5 (UNREACHABLE 0, NO-REFS 0, **WRITE-ONLY 2**, **TEST-ONLY 1**, UNUSED-RES 0, UNUSED-DEP 0, SUPERSEDED 0, **VESTIGIAL 2**)
- Also worth attention but not part of the scope files: the whole `res/values/strings.xml` notification/channel block and `R.drawable.ic_stat_zcode` are all referenced (`Notifier.kt:74-102/142/152/174-179/294/327-329/367/396-407/458`), and no `res/xml/*.xml`, manifest permission, ProGuard rule or Gradle dependency is needed by this subsystem — **no dead code found** in `LiveUpdate.kt` and `CardDismissReceiver.kt`.
- Highest-value deletions:
  1. `NotifyUpdate.running` — `android-shell/app/src/main/java/com/zcode/remote/core/NotifyState.kt:72` (write-only placeholder, the re-introduced `hasRunning` pattern).
  2. `TaskSnapshot.hasBackgroundWork` — `android-shell/app/src/main/java/com/zcode/remote/core/NotifyState.kt:17` (parsed off the wire, carried, never read).
  3. `AttentionEvent.interactionId` — `android-shell/app/src/main/java/com/zcode/remote/core/NotifyState.kt:64` (test-only reader; production de-dupes on `pendingInteractionId`).
- Estimated removable lines: ~10 production lines (F1 ~4, F2 ~5 with its writers, F3 ~3, F4 ~4, F5 ~1) — this area is small and genuinely well maintained; the previous round's cleanups left no superseded builder, no unused auto-cancel delay and no unused notification id behind.

---

## Status verification (2026-09-19)

Re-verified by grep over `app/src` + `tools/` only. All four cleanup batches landed in commit `b627610` (2026-09-18); batch markers below cite the in-code comments (B1/B3 = 第 2 批 B 级条目) or later commits.

| 条目 | 原判定 | 当前状态 | 备注 |
| --- | --- | --- | --- |
| F1 `NotifyUpdate.running` | WRITE-ONLY | **已删除** | `NotifyState.kt:85-95` 现在只有 `completed`/`attention`/`heldRunning` 三个字段；删除理由固化在 `NotifyState.kt:77-84` 与 `NotifyStateTest.kt:37`（"B1（2026-09-18）"，b627610 第 2 批 B1） |
| F2 `TaskSnapshot.hasBackgroundWork` | WRITE-ONLY | **已删除** | `TaskSnapshot`（`NotifyState.kt:10-22`）现为 6 字段，wire 副本已删；`SessionsIndexState.SessionEntry.hasBackgroundWork`（`:26`）是原报告已区分的另一独立声明，仍在且仍被序列化 |
| F3 `AttentionEvent.interactionId` | TEST-ONLY | **已删除** | `AttentionEvent`（`NotifyState.kt:67-70`）现只有 `workspaceKey`/`task` 两字段；去重按 `task.pendingInteractionId`（`:159-160`）；`NotifyState.kt:62-65` 固化了删除理由 |
| F4 `Notifier.taskIntent` 的 `workspaceKey` 参数 | VESTIGIAL | **仍在（活着）——判定反转** | `d08e256`（2026-09-18，晚于 b627610）让参数真正被消费：`Notifier.kt:496` `putExtra(EXTRA_WORKSPACE_KEY, workspaceKey)`，`MainActivity.kt:722` 读取，用于「点卡片 → 任务详情页」的 KICKED 预设通路。原建议"删参数"已失效 |
| F5 `NotifyUpdate.heldRunning = emptySet()` 默认值 | VESTIGIAL | **仍在（仍死）** | 默认值仍在 `NotifyState.kt:94`；显式传参在 `:210`，唯一读点 `TaskStore.kt:715`；`TaskStore.kt:241/730/794` 三处省略式构造照旧，`buildUpdate`（`TaskStore.kt:821`）仍只读 `completed`/`attention` |

Known / intentional 项复核：`adoptOrphanFinishedCards`（`Notifier.kt:107/124`）、`CardDismissReceiver`（manifest `:113`，`Notifier.kt:430-432`）、`CompletionEvent.failed`（`Notifier.kt:358` 读取）、`LiveUpdate` 反射 lookup 与手写 extras（`LiveUpdate.kt:56-96`；`compileSdk` 仍为 35，`app/build.gradle.kts:32`）——全部维持原判定，理由仍成立。
