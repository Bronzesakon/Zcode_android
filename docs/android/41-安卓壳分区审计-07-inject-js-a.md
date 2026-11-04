> ⚠️ Historical audit baseline (2026-09-18, pre-cleanup). Status as of 2026-09-19: see "Status verification" section at the end. Many items listed as dead have since been deleted in batches 1–4 (commit b627610).

# inject-js-a — dead-code findings

## Scope audited
- `android-shell/app/src/main/assets/inject.js` **lines 1–1105** (of 3749) — the document-start
  page-injected layer, installed by `WebViewCompat.addDocumentStartJavaScript`:
  - §0 page log sink `window.zcode.log` (`:84`)
  - §1 visibility hijack (decision D12) — **已停用** (`:218`)
  - §2 `WebSocket.prototype` hook, socket adoption, frame capture (`:250`)
  - §2b main-thread cost instrumentation (`:502`)
  - §3 protocol client wiring (`:753`)
- Cross-checked (read/grep only, no edits) against: `assets/zcode-protocol.js`,
  `app/src/main/java/com/zcode/remote/WebAppBridge.kt`, `ShellRuntime.kt`,
  `core/ControllerTasksState.kt`, `core/Prefs.kt`, `MainActivity.kt`, and `tools/inject.test.js`.
- Method: full read of 1–1105 in ≤500-line chunks; then a **declaration census** of lines 1–1105
  (122 `var`/`function` declarations, each name recounted over the whole file) plus repo-wide greps
  for every symbol not accounted for. `node --test tools/inject.test.js` → **45/45 pass** (evidence
  for the "Risk if removed" fields).

Negative result worth stating up front: **no `NO-REFS` declaration exists in lines 1–1105.** The
census flagged 40 declarations with ≤3 `\bname\b` hits in the file; every one of them was traced to
a live reader (bridge consumer, test, or later section). The findings below are dead *parameters,
fields and branches*, not dead functions.

## Findings

### F1. `injectPayload()`'s `quiet` parameter and its "no socket" debug branch are unreachable [VESTIGIAL]
- **Location**: `app/src/main/assets/inject.js:915` (declaration), `:926-931` (branch),
  `:906-914` (docstring that justifies it), sole reference at `:790`
- **Symbol**: `injectPayload(payload, quiet)`
- **Why dead**: `quiet` is never passed. It only exists to downgrade one message
  (`'暂无可用的 relay socket…'`) from warn to debug, and its documented caller — the heartbeat
  probe — no longer exists: the probe was moved to `sendControlFrame()` precisely because it must be
  a **top-level** control frame, not a `{type:'data'}` payload (see the comment at `:1469-1472` and
  the probe call at `:1478`). So `quiet` is always `undefined` and the `if (quiet)` arm at `:926-928`
  can never run; the docstring sentence "The heartbeat probe uses it" is stale.
- **Reference search**:
  - `\binjectPayload\b` over `android-shell/` → 8 hits: `README.md:964` (prose), `inject.js:790`
    (`send: injectPayload` — the only *reference*, passed as a value), `:915` (declaration),
    `:954/:959/:1469/:1471` (comments). **Zero call expressions.**
  - The only invoker is therefore `RemoteClient._send`: `\b_send\b` over `zcode-protocol.js` →
    `:1079` (assignment `this._send = options.send`), `:1352` (`self._send(payload)`),
    `:1574` (`self._send(out)`) — **both call sites pass exactly one argument**.
  - The `Bridge`/`RpcFrameSender` path is the same function: `attach(...)` at `zcode-protocol.js:1573`
    wraps it as `function (out) { self._send(out); }`, and `sendPayload(payload)` at `:519`/`:1022`
    also takes one argument.
- **Confidence**: high
- **Suggested action**: delete with the docstring — drop the parameter and the `if (quiet)` arm
  (keep the `diag('warn', …)` else-arm unconditional), and rewrite `:906-914` (9 lines) to describe
  what the function now does.
- **Risk if removed**: none functionally — the removed branch emits a line that is currently
  unreachable. `tools/inject.test.js` does **not** pin `quiet` (`quiet` in the test file → only the
  comments at `:294`, `:945`, `:867` "gives up quietly"); the neighbouring behaviours are pinned
  independently by `:666` ("our own injected frames are not re-observed") for `injecting` and `:1062`
  ("只读壳：前台心跳一帧都不写") for the probe. Same shape as the already-accepted
  `NativeRelayClient.sendBusinessPayload(payload, quiet)` finding (CONSOLIDATION-NOTES §carrier-diag #2).

### F2. `__zcodeShellWrapped` is written on both prototype wrappers and never read [WRITE-ONLY]
- **Location**: `app/src/main/assets/inject.js:341` (`wrappedSend.__zcodeShellWrapped = true;`),
  `:378` (`wrappedClose.__zcodeShellWrapped = true;`)
- **Symbol**: `__zcodeShellWrapped`
- **Why dead**: the flag looks like a double-wrap / already-hooked marker, but nothing anywhere reads
  it. The re-entry case it would guard is already handled by the module guard
  `G.__zcodeShellInstalled` (`:41`, `:46`) and by capturing `proto.send`/`proto.close` once inside
  `installWebSocketHook()`.
- **Reference search**: `__zcodeShellWrapped` over `android-shell/` (whole repo: assets, java,
  tools, README, xml) → **exactly 2 hits, both assignments** (`:341`, `:378`); no read, no
  `hasOwnProperty`/`in` test, no Kotlin `evaluateJavascript` reference
  (`grep` of the name over `app/src/main/java/**` → 0 hits).
- **Confidence**: high (on the negative). Value: low — 2 lines.
- **Suggested action**: delete both lines, or keep but annotate why the marker exists.
- **Risk if removed**: none. `tools/inject.test.js:481` pins that the wrapper keeps statics,
  prototype and `instanceof`; `:497` pins the close-log stack walk — neither touches this property.

### F3. The `convtext` bridge message carries a `frames` field no native reader consumes [WRITE-ONLY]
- **Location**: `app/src/main/assets/inject.js:676` (`frames: conv.frames` inside
  `post('convtext', {…})` at `:673-677`)
- **Symbol**: `frames` (payload field of the `convtext` event)
- **Why dead**: the value is real (`latestConversationText()` returns `frames: this._convFrames || 0`
  — `zcode-protocol.js:2783` — so it is not an accidental `undefined`), it is JSON-serialised and
  posted over the bridge, and the Kotlin handler simply never looks at it: the `"convtext"` branch
  reads only `text` and `topic`.
- **Reference search**:
  - Kotlin dispatch: `ShellRuntime.kt:1505-1517` — `data.optString("text")` (`:1512`) and
    `data.optString("topic")` (`:1514`) are the only two reads; the method returns right after
    `onConversationText(sessionId, text)`.
  - `frames` over `android-shell/**/*.kt` → 34 hits, **all** unrelated (local variables in
    `RelayBridge`/`RelayWire`/tests, `SurvivalVerdict.frames`, KDoc prose); none is a JSON key read
    from a bridge payload. No `optInt("frames")` / `optJSONArray("frames")` anywhere.
  - JS side: `convtext` in `tools/inject.test.js` → 0 hits (no test pins the shape).
- **Confidence**: high
- **Suggested action**: delete the field from the `post('convtext', …)` object (1 line).
- **Risk if removed**: none today. If the "对话帧" diagnostic is ever wanted natively, the same
  number is already available in the `页面开销` diag line via `conversationFrameStats().frames`
  (`inject.js:697`), so the field is redundant as well as unread.

### F4. `startHeartbeat()` has no caller anywhere — the whole page-side 10 s timer is dead [NO-REFS] ⚠️ CROSS-RANGE
> **This declaration is at line 1391, outside my assigned 1–1105 range.** I am reporting it because I
> had to prove the `pagelogWindowCount` reset path, whose only reset lives in `heartbeatTick()` — I
> found the timer that used to drive it is unbound. **Owner for de-duplication: the agent covering
> 1106+.** It is not counted in my summary totals.
- **Location**: `app/src/main/assets/inject.js:1391-1395` (`function startHeartbeat() { setInterval(…,
  HEARTBEAT_MS) }`), companion constant `:1129` (`var HEARTBEAT_MS = 10000;`)
- **Why dead**: the section header (`:1106-1112`) says the tick body is exposed as
  `__zcodeShellHeartbeat` and "the foreground service drives it"; the boot block
  (`:3713-3748`) never calls `startHeartbeat()`, and the Kotlin pump calls
  `window.__zcodeShellHeartbeat()` (`ShellRuntime.kt:548`) instead. `heartbeatTick()` is therefore
  reachable **only** through the native pump, and the page-side 10 s interval it was written for
  does not exist. `HEARTBEAT_MS` is read nowhere else, so it becomes VESTIGIAL with it. The
  `MIN_TICK_GAP_MS` comment at `:1150` ("so the timer and the pump cannot double up") also refers to
  this removed timer; `MIN_TICK_GAP_MS` itself stays live (pump + tests `:1019`/`:1044`).
- **Reference search**: `startHeartbeat\b` over the whole `android-shell/` tree → **exactly 1 hit:
  the declaration at `inject.js:1391`** (word-boundary pattern deliberately excludes Kotlin's
  different symbol `startHeartbeatPump`). `HEARTBEAT_MS` over the tree → 2 hits: `:1129` (declaration)
  and `:1394` (the use *inside* the dead function). No `evaluateJavascript` string in
  `app/src/main/java/**` names `startHeartbeat`.
- **Confidence**: high
- **Suggested action**: delete `:1391-1395` + `:1129`, and fix the stale clause in the `:1150` comment.
- **Risk if removed**: none for behaviour — the interval never runs. `tools/inject.test.js:1019`
  ("the native pump can drive a heartbeat, and the tick is rate limited") drives
  `__zcodeShellHeartbeat`, not `startHeartbeat`, and keeps passing.

## Known / intentional (not a new finding)
- `installVisibilityHijack()` — `inject.js:245-247`, sole body is one `diag('info', …)`; still called
  at `:3721`. Documented keep (brief §5, plus the 2026-09-16 note in `:241-243`): the historical
  spoofing implementation was deleted and only the "the shell is not lying to the page" diagnostic
  line remains. **Re-check: reason still holds — yes.** I specifically looked for *new* dead matter
  left inside §1 as instructed and found none: no residual flag, listener, or state survives
  (`SHELL_VISIBILITY_HIJACK` no longer exists in this file — the only top-level boolean left here is
  `SHELL_READ_ONLY` at `:1187`, outside my range, per CONSOLIDATION-NOTES V2). The 27-line comment at
  `:218-244` is the decision record. Deleting the stub would *not* break the pinning test:
  `tools/inject.test.js:529` asserts the *absence* of the mechanism (property descriptors untouched,
  lifecycle events delivered), so it passes with or without the stub — the stub is kept for the log
  line, not for the test.
- `config().passiveObserve === false` guards (`inject.js:296`, `:2309`) — not dead: the switch is
  real end-to-end (`Prefs.passiveObserve` @ `core/Prefs.kt:59`, set from the adb `diag_cmd passive_off`
  path at `MainActivity.kt:766`, served by `WebAppBridge.config()` at `WebAppBridge.kt:55`, and
  `configDefaults()` at `inject.js:200` matches the Kotlin fallback `WebAppBridge.kt:61`). Both
  unguarded callers (`installWebSocketHook` `:3726`, `startConversationStreamMonitor` `:2310`) are
  live.
- `pageCoverage` (`inject.js:772-781`) declares 5 of the 7 maps `RemoteClient` shares — `pageBridges`
  and `pageOwned` are deliberately absent because `zcode-protocol.js:1125-1140` *attaches* any
  missing map onto the caller's object; this is documented at `zcode-protocol.js:1125-1131`.
  Not a finding.

## Suspected but unproven
- `socketPaths` — `inject.js:409`, written at `:425`, read at `:426` only as
  `socketPaths[path] === 1` to emit the "first time this socket type appeared" line. The accumulated
  count beyond the 1→2 transition is never surfaced anywhere (no post, no diag, no Kotlin reader).
  Missing evidence: whether the counter's *totals* were meant to be reported (the comment at `:418-422`
  frames it as a diagnostic question that was later answered elsewhere). Low value; I do **not**
  claim it dead — the value is genuinely read for the transition test.
- `knownSockets` capability probe — `inject.js:382` (`typeof WeakSet === 'function' ? new WeakSet() :
  null`) makes the `sockets.indexOf` fallback at `:388` and the `if (knownSockets)` guard at `:433`
  unreachable on any platform this app can actually run on (`WeakSet` shipped in Chromium 36/2014;
  `minSdk 26` WebView is Chrome 60+). Missing evidence: no device run to prove
  `typeof WeakSet === 'function'` is always true in the shipped WebView, and it is a deliberate
  defensive probe. If it were dropped it would save ~4 lines. Low value.
- Not reported here (owned by the 1106+ agent, flagged for their attention rather than audited by me):
  the heartbeat/`SHELL_READ_ONLY` block `:1129-1200`, the `forceReconnect`/`nudgeReconnect`/
  `stallReloadIfAllowed` stubs, and the liveness payload fields consumed by `onLiveness`.

## Summary
- Findings in range 1–1105: **3** — UNREACHABLE 0 (one dead branch, counted inside F1 as VESTIGIAL),
  NO-REFS 0, WRITE-ONLY 2 (F2, F3), VESTIGIAL 1 (F1). Plus **1 cross-range NO-REFS (F4)** owned by the
  1106+ agent, listed but not counted.
- Highest-value deletions:
  1. `inject.js:915/926-931` + docstring `:906-914` — delete `injectPayload`'s `quiet` parameter; its
     only documented caller moved to `sendControlFrame()` (`:1478`), so the silent path is unreachable.
  2. `inject.js:1391-1395` (+ `HEARTBEAT_MS` @ `:1129`) — `startHeartbeat()` appears exactly once in
     the entire repo: its own declaration (cross-range, dedupe with the 1106+ owner).
  3. `inject.js:676` — drop the `frames` field from the `convtext` bridge message; Kotlin reads only
     `text` and `topic` (`ShellRuntime.kt:1512/1514`).
- Estimated removable lines: **~12 in range** (F1 ~9 incl. doc rewrite, F2 2, F3 1) — plus ~7 if the
  cross-range F4 is taken.
- Explicit negative: the two large "third-party-trust" areas of my range are clean — every
  `post()` event name this range can emit (`diag`, `pagelog`, `relaycreds`, `sessions`,
  `controllertasks`, `status`, `pagews`, `convtext`) has a live branch in
  `ShellRuntime.onBridgeMessage` (`:1479-1588`), the bridge object name `ZCodeShell`
  (`inject.js:63`) matches `MainActivity.BRIDGE_NAME` (`:956`), and both bridge methods this range
  calls (`postMessage`, `config`) still exist in `WebAppBridge.kt` (`:23`, `:42`) — **no dead wiring,
  no missing native method.** `node --test tools/inject.test.js` = 45 pass / 0 fail.

## Status verification (2026-09-19)

Verified against the live tree: `android-shell/app/src/main/assets/inject.js` is now **3721 lines**
(HEAD `2c89b88` + uncommitted 档0观测 addition of `__zcodeShellViewSnapshot`). Original text above
is unchanged. Batch attribution follows the git history of the file.

| 条目 | 原判定 | 当前状态 | 备注 |
| --- | --- | --- | --- |
| F1 `injectPayload` 的 `quiet` 形参 + no-socket 分支 | VESTIGIAL | 已删除 | 批次1–4（b627610）；`:886` 留有删除记录注释，函数现为单参 `injectPayload(payload)`（`:892`） |
| F2 `__zcodeShellWrapped` 双写不读 | WRITE-ONLY | 已删除 | b627610；现全仓 0 命中 |
| F3 `convtext` 的 `frames` 字段 | WRITE-ONLY | 已删除 | b627610；`post('convtext')` 现只发 `{topic, text}`（`:671-674`），帧计数改走 `conversationFrameStats` 诊断行（`:679-681`） |
| F4（跨段）`startHeartbeat` + `HEARTBEAT_MS` | NO-REFS | 已删除 | b627610（即 B-F1）；心跳唯一入口为原生泵 `__zcodeShellHeartbeat`（`:1492`）；`MIN_TICK_GAP_MS` 仍活（`:1123`，读者 `:1381`） |
| known: `installVisibilityHijack` | 有意保留 | 仍在（活着） | `:245`，boot 调用 `:3694`；"壳不骗页面"理由仍成立 |
| known: `passiveObserve` 闸门 | 非死代码 | 仍在（活着） | 闸门点只剩一处（`installWebSocketHook` `:296`）；另一调用方 `startConversationStreamMonitor` 已随 §5c 决策机删除（批次4b）；开关端到端仍通（`Prefs.kt:59` / `WebAppBridge.kt:60` / `MainActivity.kt:864`） |
| known: `pageCoverage` | 非 finding | 仍在（活着） | `:769`，`:805` 传入协议共享状态 |
| suspected: `socketPaths` | 疑点未证实 | 仍在（疑点维持） | `trackSocket` `:409`；状态未变 |
| suspected: `knownSockets` WeakSet 探测 | 疑点未证实 | 仍在（疑点维持） | `socketKnown` `:382`；状态未变 |

小结：9 个顶层条目 —— 已删 4（F1–F4，全部落在 b627610），仍活 3，疑点维持 2。原文的负结论
（1–1105 内不存在 NO-REFS 声明）复核后仍成立；本段现已无已知死代码。
