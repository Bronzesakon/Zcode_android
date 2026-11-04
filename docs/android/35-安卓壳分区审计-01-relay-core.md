> ⚠️ Historical audit baseline (2026-09-18, pre-cleanup). Status as of 2026-09-19: see "Status verification" section at the end. Many items listed as dead have since been deleted in batches 1–4 (commit b627610).

# relay-core — dead-code findings

## Scope audited
- `android-shell/app/src/main/java/com/zcode/remote/core/RelayBridge.kt` (1510 lines) —原生承载 bridge protocol engine: `RelayChannel` (ChannelClient state machine), `BridgeSession` (rpc-frame send/ack + 4-step handshake + conversation/sessions-index subscribe + reanchor), `BridgeManager` (workspace-list → open/subscribe/recycle/rotate), `RelayTaskDigest` (activity discovery digest).
- `android-shell/app/src/main/java/com/zcode/remote/core/RelayWire.kt` (752 lines) — wire data plane: varint/CRC32 value codec, rpc-frame fragment/reassembly, logical-frame assembly, conversation row-tail → progress text.

Cross-reference scope (every "dead" claim below): `android-shell/app/{src/main,src/test}`, `android-shell/tools/*`, `android-shell/app/src/main/assets/{inject.js,zcode-protocol.js}`, `android-shell/docs/**`, plus a whole-workspace grep (`E:\Zcode_harmony`) for the one private field. No build/compiler evidence was obtained (Gradle cannot run in this sandbox) — all claims are reference-graph claims.

**Note on the NO-REFS category:** both files were already harvested for zero-caller symbols by the round recorded in `docs/18` §2.1 — `RelayBridge.hasConversationSubscriptions()`, `RelayWire.requestBody()`, `RelayWire.listenBody()`, `RelayWire.REQ_PROMISE_CANCEL` are gone (I re-checked: zero hits for all four anywhere). What remains in these two files is reachable-by-graph but dead-by-effect; no new NO-REFS declaration was found.

## Findings

### F1. `idToKey` — a bridge-session→workspace index that is only ever written [WRITE-ONLY]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/RelayBridge.kt:1057` (declaration); writes at `:1424`, `:1425`, `:1446`; removes at `:1135`, `:1293`; clear at `:1494`
- **Symbol**: `BridgeManager.idToKey` (`private val idToKey = ConcurrentHashMap<String, String>()`)
- **Why dead**: every one of the 7 sites is a mutation (`[]=`, `remove`, `clear`); nothing ever performs a lookup. Inbound frame routing uses `bridgesById[...]` only (`:1345`), and the key it would yield is already on the object (`BridgeSession.workspaceKey`). `:1424` and `:1446` even assign the same `actualId → key` pair twice in the same function, so it is not serving as an insert-order or "last known key" cache either.
- **Reference search**: `idToKey` over the **whole workspace** `E:\Zcode_harmony` → **7 hits, all in RelayBridge.kt**, all mutations: 1 declaration, 3 puts (1424/1425/1446), 2 `remove` (1135/1293), 1 `clear` (1494). Zero `idToKey[...]` reads, zero `idToKey.get`, zero `.keys/.values/.forEach`. Cross-checks: not named in `docs/**` (unlike the other known write-only Kotlin fields listed in `docs/18` §3.9 ④: `TaskSnapshot.hasBackgroundWork`, `Workspace.source`, `SessionEntry.parentSessionId`), no reflection (`getDeclaredField`/`getDeclaredMethod`/`Class.forName` over `app/src/main/java` → only unrelated `Intent(..., X::class.java)` and one `NotificationManager.getMethod` in `notify/LiveUpdate.kt:72`), no JS/javscript-interface exposure, and it is `private` so no test can touch it.
- **Confidence**: high
- **Suggested action**: delete the field and its 6 mutation sites (1135, 1293, 1424, 1425, 1446, 1494)
- **Risk if removed**: none. Nothing reads it; no test references it; it is not part of the wire format (pure in-process bookkeeping). The live index `bridgesById` is untouched.

### F2. Three orphaned KDoc blocks — two of them document deleted functions [VESTIGIAL]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/RelayBridge.kt:555-562`, `:566`, `:886-890`
- **Symbol**: floating KDoc blocks (no declaration to attach to)
- **Why dead**:
  - `:555-562` — "周期性重挂：**退订后重订阅**，逼桌面端再推一份 snapshot" describes the *old* reanchor mechanism. It sits directly above `conversationSessionIds()` (`:564`), which it does not describe; the live reanchor at `:570-585` explicitly documents the opposite behaviour ("**保留**对话订阅，在同一 subscription 上强制请求新 snapshot"). `docs/18:136` records the rename `BridgeSession.reanchorConversations` → `reanchorConversationsBlocking(): String?` — the old doc was left behind.
  - `:566` — "桥是否还挂着对话订阅（回收决策用：没挂过就没什么可重锚的）" is the KDoc of `hasConversationSubscriptions()`, which `docs/18` §2.1 (`:38`) records as deleted for having zero callers. It now dangles above `scopeCopy()` (`:568`), whose own doc is on `:567`.
  - `:886-890` — the `BridgeManager` class KDoc ("桥管理器（对应 JS RemoteClient.start 的主动订阅面）…") is stranded 148 lines above `class BridgeManager` (`:1038`), separated by the three reanchor constants and `RelayTaskDigest`. Because `:892` is the doc comment that immediately precedes `REANCHOR_GAP_MS` (`:893`), the 886-890 block attaches to nothing while `BridgeManager` itself has no KDoc.
- **Reference search**: `退订后重订阅` / `桥是否还挂着对话订阅` / `hasConversationSubscriptions` / `桥管理器` over `android-shell` → 4 hits total: RelayBridge.kt:556, :566, :887 and `docs/18:38`. The docs hit is the deletion record, not a reader; the other three are the orphan comments themselves. Between `:884` (`}` closing `BridgeSession`) and `:1038` (`class BridgeManager`) there is no other candidate declaration these blocks could belong to.
- **Confidence**: high
- **Suggested action**: delete `:555-562` and `:566`; move `:886-890` down to sit immediately above `:1038` (keep the text)
- **Risk if removed**: zero — comments only, no code, no test pins them. Leaving them is actively misleading: an IDE hover on `conversationSessionIds()` shows "退订后重订阅", and on `scopeCopy()` shows a "does this bridge still have conversation subscriptions" predicate that does not exist.

### F3. `subscribeConversationProgressInternal(fromReanchor)` — parameter never read [VESTIGIAL]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/RelayBridge.kt:504` (declaration), single call site `:501`
- **Symbol**: `fromReanchor: Boolean`
- **Why dead**: the whole body (`:504-553`) never mentions `fromReanchor`; the public/private split exists only to thread a flag that the reanchor rewrite (`:586 reanchorConversationsBlocking`, which does its own `resyncConversationV4(forceSnapshot)` instead of re-subscribing) stopped using. The only value ever passed is the literal `false` on `:501`.
- **Reference search**: `fromReanchor` over `android-shell` → **2 hits**: the declaration (`:504`) and `fromReanchor = false` (`:501`). `subscribeConversationProgressInternal` → 2 hits (same two lines) — the private function has exactly one caller, in the same file.
- **Confidence**: high
- **Suggested action**: delete the parameter (or collapse the private function into `subscribeConversationProgress`, its only entry point)
- **Risk if removed**: none. No caller passes `true`; no test calls the private form. `subscribeConversationProgress` keeps its public signature (used by `BridgeManager.beginCoverage`/`recoverConversation`/`BridgeManager.subscribeProgress` and reachable from `NativeRelayClient.subscribeProgress` ← `ShellRuntime.kt:340`).

### F4. `FrameAssembler.accept`'s `rpc-frame-ack` branch can never be taken [UNREACHABLE]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/RelayWire.kt:409` (inside the `when (payload.optString("zcode_type"))` at `:408-412`)
- **Symbol**: the `"rpc-frame-ack" -> return null` arm
- **Why dead**: the only production caller is `BridgeSession.acceptBridgePayload` (`RelayBridge.kt:148`), which is itself inside `when (payload.optString("zcode_type")) { "rpc-frame" -> … }` (`:146-153`) — so the value is already pinned to `"rpc-frame"` when the assembler sees it. An incoming ack instead falls into `acceptBridgePayload`'s own `else -> Unit` (`:152`), so it never reaches `accept()`. The `else -> return null` arm (`:411`) *is* live and is pinned by a test; the ack arm is not.
- **Reference search**: `FrameAssembler|assembler\.accept` over `android-shell` → production sites are exactly `RelayBridge.kt:130` (construction) and `:148` (call); all other hits are `RelayWireTest.kt:139/152/161/174` and `RelayBridgeTest.kt:176/193/271/326/328`. `rpc-frame-ack` → `RelayBridge.kt:166` (we *build* acks), `:1344` (routing), `RelayWire.kt:409`, `RelayBridgeTest.kt:188` (test asserts we *send* acks). No test ever feeds an ack to `accept()`: `RelayWireTest.kt:154-155` ("foreign bridge and non-frame payloads are ignored") passes a `workspace-bridge-ready` payload, which exercises the `else` arm, not the ack arm.
- **Confidence**: high on unreachability; low value (1 line)
- **Suggested action**: keep but annotate — or delete, accepting divergence from the reference (the Kotlin file is a deliberate port of `assets/zcode-protocol.js:540`, which has the identical `if (type === 'rpc-frame-ack') return true;` guard, and `RelayWireTest` pins byte/behaviour parity with it)
- **Risk if removed**: none functionally (the caller already filters); the only cost is that the port stops mirroring the JS guard.

### F5. `ConversationTail.applyStatePatch` — an empty function whose `when` branch does nothing [VESTIGIAL]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/RelayWire.kt:727-730` (declaration, empty body, parameter `op` unused), call site `:668`
- **Symbol**: `applyStatePatch(op: JSONObject)` and the `"state.updated" ->` arm
- **Why dead**: the body is empty (`// The tail only models rows, so there is deliberately nothing to mutate.`) and `applyDeltas`'s `when (op.optString("op"))` (`:641-669`) has **no `else`**, so an unmatched op already falls through silently. Deleting both the arm and the function is therefore behaviour-preserving; the only remaining artefact is a parameter the compiler must warn about.
- **Reference search**: `applyStatePatch` over `android-shell` → **2 hits**, `RelayWire.kt:668` and `:727`. `state.updated` over `android-shell` → `RelayWire.kt:615` (KDoc listing implemented ops), `:668`; nothing in `app/src/test/**`, `tools/*.test.js` or the JS assets asserts the branch (test `conversation tail removes suffix and ignores unknown upsert` covers `row.removed`/`row.upserted` only).
- **Confidence**: high
- **Suggested action**: delete the arm + function, keeping the explanatory sentence as a comment on the `when` block — or, if the deliberate no-op is wanted as documentation of the wire op, at least drop the unused `op` parameter
- **Risk if removed**: none (no-op). This is *not* on the `docs/18` §4/§5 keep-list, so it is a new finding rather than a re-report; but the in-body comment shows it was intentional, so the call is a style decision.

### F6. Production defaults that only tests exercise [TEST-ONLY]
- **Location**: `android-shell/app/src/main/java/com/zcode/remote/core/RelayBridge.kt:193` and `android-shell/app/src/main/java/com/zcode/remote/core/RelayWire.kt:387`
- **Symbol**: `BridgeSession.runHandshake(progressSessions: List<String> = emptyList())` default; `RelayWire.FrameAssembler(onLog: (String) -> Unit = {})` default
- **Why test-only**: production always passes an explicit argument — `runHandshake(runningSessions(...))` (`RelayBridge.kt:1125`) and `runHandshake(sessions)` (`:1310`); the `runHandshake()` zero-arg form appears only at `RelayBridgeTest.kt:122`. Likewise `FrameAssembler` is always constructed with `onLog = onLogLine` in production (`RelayBridge.kt:130`); the `{}` default is used only by `RelayWireTest.kt:139/152/174`.
- **Reference search**: `runHandshake` over `android-shell` → RelayBridge.kt:193/1125/1310 + `RelayBridgeTest.kt:122`; `FrameAssembler(` over `android-shell` → RelayBridge.kt:130 + `RelayBridgeTest.kt:176/326` + `RelayWireTest.kt:139/161/174` (only `:161` passes `onLog`).
- **Confidence**: high (mechanism), low value
- **Suggested action**: keep but annotate — these are harmless ergonomic defaults; do **not** delete them without updating the two tests
- **Risk if removed**: only the test call sites (`RelayBridgeTest.kt:122`, `RelayWireTest.kt:139/152/174`) would fail to compile. Reported separately rather than as flatly dead, per the brief.

## Known / intentional (not a new finding)

- **`controllerStreamEnabled = false` chain** — `RelayBridge.kt:364` (flag), the guarded subscriber `:370-415`, `onControllerWire` `:417-428`, `resyncController` `:430-457`, `unsubscribeController` `:459-486`, the fields `:337-341` (`controllerState`, `controllerListenerId`, `controllerSubscriptionId`, `controllerResyncing`, `installingControllerListener`), the callbacks `BridgeSession.onLiveTasks` `:124` / `BridgeManager.liveTaskSink` `:1047` and their only invocation `:422`. Documented in `docs/18` §4 (`:713`) as kept for "协议 schema 与单测的依据". **Re-check — reason partly holds, partly does not:**
  - *Still holds* for `RelayWire.kt:76-80` (`TOPIC_CONTROLLER_TASKS` / `EVENT_CONTROLLER_FRAME` / `METHOD_SUBSCRIBE|UNSUBSCRIBE|RESYNC_CONTROLLER`, all only used inside the sealed chain or by tests) and for **`ControllerTasksState`**, which is genuinely live on a different path: `ShellRuntime.kt:1611-1632` (`pageControllerTasks` ← page's own `controller/tasks-index` frames) → `TaskStore.applyLiveTasks` (`TaskStore.kt:240`). Deleting the state machine would break the third run-state source, so the §4 keep is correct for it.
  - *No longer holds* for the RelayBridge-side subscriber itself (`:370-486`, ~90 code lines + comments): nothing reads `controllerSubscriptionId` except the unreachable code itself, so `unsubscribeController`'s body after `:460` is unreachable too. The claimed "单测依据" is absent — `RelayBridgeTest`'s scripted desktops contain controller arms (`:200-205`, `:213-224`, `:335-339`, `:346-357`) that can never fire because the client never sends `subscribeControllerV4` nor registers `onDynamicControllerFrame`; and `docs/18` §3.9 ⑤ (`:384-385`) itself concedes these paths have zero Kotlin-side coverage. Downstream dead end for the same reason: `NativeRelayClient.liveTaskSink` (`NativeRelayClient.kt:199`, wired at `:502-508`) and its consumer `ShellRuntime.kt:425-437` can only be invoked from `:422`. If the controller stream is abandoned for good, the removable unit is `RelayBridge.kt:337-341, 370-486` + `NativeRelayClient.liveTaskSink` + `ShellRuntime.kt:425-437` — **not** `ControllerTasksState` / `applyLiveTasks` / the `RelayWire` constants.
- **`RelayWire.METHOD_ROWS_RANGE`** (`RelayWire.kt:49`) — `docs/18` §4 (`:715`) annotates it as a recorded dead end (native side always times out). Re-check: `METHOD_ROWS_RANGE` over `android-shell` → **1 hit, the declaration itself**. Reason still holds — the 6-line comment is the only thing stopping a future round from re-adding the pull path, and the constant is free. Keep as is.
- **`RelayChannel` / `BridgeSession` / `BridgeManager` public visibility** — the three classes and every `RelayChannel` method are `public` but only referenced from within `RelayBridge.kt` (`RelayChannel` constructed at `:131`; every external call is a `channels.` call at `:148/194/201/220/232/290/381/384/394/444/464/475/525/623/676/679/782/810/831/859/873`, and `markInitialized()` only at `:83` — grep `\.channels|markInitialized|awaitReady|removeListener|handleBodyBytes|listenEvent|callBlocking` over `android-shell` hits nothing else in Kotlin; the other hits are `zcode-protocol.js`'s own unrelated `bridge.channels`). This is an API-surface nit, not dead code, and was not counted above.

## Suspected but unproven

- **`RelayWire.decodeValue` tag `2`** (`RelayWire.kt:306`, the `2, 3 ->` arm) — neither our encoder (`encodeValue`, which only emits tag 3 for `ByteArray`, `:210-212`) nor the JS reference (`zcode-protocol.js:406-409`) ever emits tag 2; the reference's *decoder* accepts it too (`:440-447`). Missing evidence: whether the desktop bundle's encoder can emit tag 2 for some Buffer-like payload — I have no device and the bundle is not in this repo, so the arm may still be load-bearing for inbound frames. Do not delete on this evidence.
- **`BridgeManager.acceptRelayPayload`'s `"rpc-frame-ack"` routing** (`RelayBridge.kt:1344`) — routing acks into `BridgeSession` ends in a no-op (`:152 else -> Unit`), so this arm has no observable effect. Missing evidence: whether the desktop ever sends acks we would want to observe/track (we do not implement ack accounting at all), i.e. whether this is deliberate tolerance or leftover plumbing. Harmless either way; not reported as a finding.

## Summary
- Findings: 6 (UNREACHABLE 1, NO-REFS 0, WRITE-ONLY 1, TEST-ONLY 1, VESTIGIAL 3)
- Highest-value deletions:
  1. `RelayBridge.kt:1057` `idToKey` — a private index with 6 mutations and zero reads (delete the field + sites 1135/1293/1424/1425/1446/1494).
  2. `RelayBridge.kt:555-562` + `:566` orphaned KDocs (one describing the superseded "unsubscribe-then-resubscribe" reanchor, one the deleted `hasConversationSubscriptions()`) — actively mis-describe `conversationSessionIds()`/`scopeCopy()`; `:886-890` additionally needs relocating down to `:1038`.
  3. `RelayBridge.kt:504` `fromReanchor` — a parameter the body never reads, with a single `false` call site at `:501`.
- Estimated removable lines: ~22 (F1 7, F2 9 + 5 relocated, F4 1, F5 5), plus one unused parameter (F3).
- Zero new NO-REFS declarations in either file: the zero-caller symbols here were already deleted in the round recorded in `docs/18` §2.1; I re-verified all four are absent.

## Status verification (2026-09-19)

Grep scope: `android-shell/app/src`, `android-shell/app/src/main/assets`, `android-shell/tools/` (docs/miuix excluded). Current file sizes: RelayBridge.kt 1342 lines, RelayWire.kt 767 lines. Deletion batch attribution follows the b627610 commit message (批1 文档/接回就绪位；批2 A/B/C 级机械清理；批3 合并诊断排队；批4 删 controller 接线与桥路由/自订阅簇).

| Item | Original verdict | Current status | Notes |
| --- | --- | --- | --- |
| F1 `BridgeManager.idToKey` | WRITE-ONLY dead | 已删除 | b627610 批2. Zero hits in app/src; the 7 mutation sites all gone. |
| F2 three orphaned KDoc blocks | VESTIGIAL | 已删除 | b627610 批2. Old texts gone; "桥管理器" KDoc now sits directly above `class BridgeManager` (RelayBridge.kt:875-879). |
| F3 `subscribeConversationProgressInternal(fromReanchor)` | VESTIGIAL | 已删除（参数） | b627610 批2. `fromReanchor` zero hits. The public/private split itself was kept: `subscribeConversationProgressInternal(sessionId)` still at RelayBridge.kt:350/353 — the audit's "collapse the function" alternative was not taken. |
| F4 `FrameAssembler.accept` `rpc-frame-ack` arm | UNREACHABLE | 已删除 | b627610 批2. `accept()` now only `"rpc-frame" -> Unit` / `else -> return null` (RelayWire.kt:409-412); the JS mirror `zcode-protocol.js:456` keeps its ack guard, so the port no longer mirrors that one guard. |
| F5 `ConversationTail.applyStatePatch` | VESTIGIAL (delete) | 仍在（保留并注释） | b627610 批2 kept it with a new KDoc at RelayWire.kt:731-741: "到得了的、刻意的空转——不是死代码，别删". `op` param still unread. The audit's delete suggestion was explicitly rejected. |
| F6 `runHandshake()` / `FrameAssembler(onLog)` defaults | TEST-ONLY | 仍在（仍死，TEST-ONLY） | Defaults unchanged (RelayBridge.kt:196, RelayWire.kt:389); still exercised only by RelayBridgeTest.kt:122 and RelayWireTest.kt:139/152/174. |
| Known: `controllerStreamEnabled` chain | keep (protocol-schema) | 已删除（RelayBridge 侧）/ 仍活（其余） | b627610 批4 removed the whole subscriber chain: `subscribeControllerTasks`/`onControllerWire`/`resyncController`/`unsubscribeController`/`onLiveTasks`/`BridgeManager.liveTaskSink`/`NativeRelayClient.liveTaskSink`/ShellRuntime lambda — all zero hits; only tombstone comments remain (ShellRuntime.kt:991-992, tools/protocol.test.js:876). Kept as predicted: `RelayWire` constants (RelayWire.kt:76-80, pinned by ControllerTasksStateTest) and `ControllerTasksState`, alive via the page path `ShellRuntime.pageControllerTasks` (:2034) → `TaskStore.applyLiveTasks` (:2054). |
| Known: `RelayWire.METHOD_ROWS_RANGE` | keep as recorded dead end | 仍在（仍死，参照保留） | Unchanged, RelayWire.kt:49, still only the declaration + comment. |
| Known: `RelayChannel`/`BridgeSession`/`BridgeManager` public visibility | API-surface nit | 仍在 | Classes still public (RelayBridge.kt:17/113/879); no external callers added. |
| Suspected: `decodeValue` tag 2 | unproven | 仍在 | `2, 3 ->` arm unchanged at RelayWire.kt:306. |
| Suspected: `acceptRelayPayload` ack routing | unproven, no observable effect | 仍在且已增强 | Now a combined `"rpc-frame", "rpc-frame-ack" ->` arm (RelayBridge.kt:1181-1190) that also buffers frames into `preOpenBuffer` during the Initialize race window (replayed at :1284). Acks still end in `acceptBridgePayload`'s `else -> Unit` (:148-150), so the original "no observable effect" note still holds for the ack half. |

Tally: 11 items — 已删除 4（F1–F4）, 仍在·仍死/保留 6, 混合 1（controller 链：RelayBridge 侧删、常量与 ControllerTasksState 仍活）.
