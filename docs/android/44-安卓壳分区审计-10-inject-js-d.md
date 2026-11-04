> ⚠️ Historical audit baseline (2026-09-18, pre-cleanup). Status as of 2026-09-19: see "Status verification" section at the end. Many items listed as dead have since been deleted in batches 1–4 (commit b627610).

# inject-js-d — dead-code findings

## Scope audited
- `android-shell/app/src/main/assets/inject.js:2659-2959` — tail of section 5: the adb-only diagnostic composer
  (`findComposer`, `describeElement`, `G.__zcodeShellProbeComposer`, `findSendButton`, `fillComposer`,
  `G.__zcodeShellComposeSend`) and `G.__zcodeShellDiag` (the diag command dispatcher).
- `android-shell/app/src/main/assets/inject.js:2962-3115` — unnumbered blocks: `G.__zcodeShellSetAppForeground`
  (foreground handler), `reportPageState`, `scrollbarReport`, `reportViewport`, `G.__zcodeShellReportLiveness`.
- `android-shell/app/src/main/assets/inject.js:3117-3329` — **section 6**, page visual state → native status-bar surface.
- `android-shell/app/src/main/assets/inject.js:3331-3708` — **section 7**, overlay scrollbar.
- `android-shell/app/src/main/assets/inject.js:3710-3749` — boot block (install calls, `ready` / liveness posts,
  viewport + load-timing timers).
- `android-shell/app/src/main/assets/inject.js:37-63` — read only to audit the idempotency guard (`__zcodeShellInstalled`)
  that the orchestrator asked about for the "closing blocks".

Cross-checks performed outside the range (reads/greps only, no other range audited in full):
`tools/inject.test.js`, `tools/cdp.mjs`, `app/src/main/java/com/zcode/remote/{MainActivity,ShellRuntime,SettingsActivity}.kt`,
`core/PageBarColor.kt`, `README.md`, `docs/18-全仓审计-错误路线残留与清理.md`, git history of the file.

Verification runs: `cd android-shell; node --test tools/inject.test.js` → **45/45 pass**.

### Range-wide negative results (so the orchestrator does not re-audit these)
- **No `NO-REFS` declaration exists in 2659-3749.** Sweep: every `function NAME` / `var NAME` declared in the range was
  counted with `\bNAME\b` over `app/src/main/**` (excluding `docs/`, `scratch/`) + `tools/**` + `README.md`; every
  symbol had ≥ 2 hits (declaration + at least one use). The 20 symbols that had exactly 2 hits were each re-read and
  confirmed to have exactly one real use (`BAR_COLOR_FALLBACK`, `BAR_FADE_MS`, `BAR_Z`, `barElement`, `barHide`,
  `barOnFrame`, `barPaint`, `barReportGeometry`, `barSkippedHere`, `fillComposer`, `findSendButton`,
  `installOverlayScrollbar`, `noteChatViewEntered`, `pageStateName`, `pageThemeName`, `pushPageState`, `scheduleFrame`,
  `SCROLLBAR_CSS`, `scrollbarReport`, `vOverlap`).
- **Section 6's payload has no dead field.** `post('pagestate', {state, theme})` (inject.js:3211) is consumed at
  `ShellRuntime.kt:1572-1578` → `onPageState(data.optString("state"), data.optString("theme"))` → `PageBarColor.stateOf/themeOf`
  (`core/PageBarColor.kt:55-60`; both `PageBarState` tokens and both `PageTheme` tokens are read there). The sibling
  diagnostic payloads in this range are also fully consumed: `post('diag', {level, message})` → `ShellRuntime.kt:1485`,
  `post('ready', {href})` → `ShellRuntime.kt:1585`.
- **No abandoned scrollbar version is left in the code.** `git show 43c6998` (the full revert) removed v1 *including* its
  `SCROLLBAR_CSS` (`scrollbar-width:thin` + 2px rail), its `installScrollbarWidth` and its boot call; `18968b1` (plan A)
  re-added a single zero-width implementation. Today's file declares exactly **one** scrollbar CSS string
  (`SCROLLBAR_CSS`, 3375-3378), **one** style installer, **one** bar builder and **no branch choosing between treatments**
  (the abandoned `border:3px solid transparent` + `background-clip:padding-box` thumb is gone; only the explanatory comment
  survives at 3530-3537). Nothing to delete here beyond F4.
- **The idempotency guard and every export in the range are live.** `__zcodeShellInstalled` is set and read only at
  inject.js:41/46 (MainActivity.kt:406 is a comment) — it is the guard itself, not dead. Exports reached from native:
  `__zcodeShellSetAppForeground` → `ShellRuntime.kt:223,261,1315,1319`; `__zcodeShellReportLiveness` →
  `ShellRuntime.kt:589`, `SettingsActivity.kt:107`; `__zcodeShellReportPageState` → `MainActivity.kt:600`;
  `__zcodeShellDiag` → `MainActivity.kt:800`. `__zcodeShellPageStateHooked` (3287/3300) is a real re-entrancy guard —
  `installPageStateReporter` is called twice (boot 3716 + the `DOMContentLoaded` retry at 3297), pinned by
  `inject.test.js:1252`.

## Findings

### F1. Dead tail after the read-only early return: `forceReconnect` call in `__zcodeShellSetAppForeground`  [UNREACHABLE]
- **Location**: `android-shell/app/src/main/assets/inject.js:3006-3010` (inside `G.__zcodeShellSetAppForeground`, 2963-3030)
- **Symbol**: the `forceReconnect(...)` / `reportPageState()` / `return` tail of the dead-link branch
- **Why dead**: the branch body is entered only when `silence < 0 || silence > RESUME_DEAD_LINK_MS`, and its first
  statement is `if (SHELL_READ_ONLY) { …; return; }` (2995-3005). `SHELL_READ_ONLY` is `var SHELL_READ_ONLY = true;`
  (1187) and is **never reassigned**. So lines 3006-3010 can never execute. The callee is itself already a documented
  stub — `function forceReconnect(reason) { noteReadOnlyRefusal(...); return false; }` (1494-1497, comment 1488-1493:
  "永不执行… 历史实现已删除") — so even a hypothetical reach would only log a refusal.
- **Reference search**: `SHELL_READ_ONLY` over `android-shell/**` → 8 hits: comments at inject.js:197,902,1144,1489;
  the declaration at 1187; the two guards at 2995 and 3015; README.md:454. **No assignment other than 1187**, and the
  test harness cannot flip it (`tools/inject.test.js:26,393-394` `require()`s the file as-is — no `readFileSync`/`vm`/`.replace`
  rewriting anywhere in the file). For the callee: `forceReconnect` over the same tree → inject.js:353(comment), 1171,1173
  (comments), 1464, 1494, 3006, 3012 (comment).
- **Confidence**: high
- **Suggested action**: delete lines 3006-3010 (keep the read-only branch above it)
- **Risk if removed**: none. No test touches this branch — `inject.test.js:1089` and `:1069` pin the *opposite*
  (read-only) behaviour, i.e. "回前台对活的链路连一帧都不写". Deleting it removes a write path that cannot happen.

### F2. Dead tail after the read-only early return: fabricated ack + `pair_status_query` in the same function  [UNREACHABLE]
- **Location**: `android-shell/app/src/main/assets/inject.js:3022-3030` (same function as F1)
- **Symbol**: `lastPairAckAt = Date.now(); staleTicks = 0; sendControlFrame({type:'pair_status_query', …}); reportPageState();`
- **Why dead**: straight-line code after `if (SHELL_READ_ONLY) { …; return; }` (3015-3021) inside the *live-link* path —
  same constant-true guard as F1, so it can never run. The comment immediately above it (3012-3014) says the
  "pretend an ack arrived" write was the self-deception they removed; the write is in fact still present, just unreachable.
  Corroborating formatting leftover: the function's closing brace is jammed onto the last statement —
  `reportPageState();    };` (3030) — the artifact of a deletion whose tail was never re-indented.
- **Reference search**: `SHELL_READ_ONLY` → same 8 hits as F1, no writer besides 1187. `sendControlFrame` over
  inject.js → also called from the heartbeat path (1478) and elsewhere, so only *this* site is dead, not the helper.
  `lastPairAckAt` → inject.js:1017,1155,1457,1461,1464,2508,2509,3012(comment),3022 — live elsewhere.
  `staleTicks` → 1158,1458,1459,1462,1467 (read/written in `heartbeatTick`), 3016,3023 — live elsewhere.
- **Confidence**: high
- **Suggested action**: delete lines 3022-3030 (keep the read-only branch above it)
- **Risk if removed**: none for behaviour (unreachable) and none for tests. It is the only `pair_status_query` site in
  this function; the live one is at 1478 behind `!appForeground`, which README:454 documents as write path ②.

### F3. Orphaned jsdoc block with no function attached  [VESTIGIAL]
- **Location**: `android-shell/app/src/main/assets/inject.js:3045-3053`
- **Symbol**: the comment `/** Reports the CSS viewport metrics. … if innerWidth * devicePixelRatio does not match the WebView's own width … */`
- **Why dead**: it is immediately followed by another jsdoc block (3054-3065, the `scrollbarReport` doc), which is what
  actually documents the next declaration (`function scrollbarReport()` at 3066). The text describes `reportViewport`
  (3085-3105), which has its own inline comment and now sits ~30 lines below its documentation.
- **Reference search**: direct read of 3043-3066 — two consecutive `/** … */` blocks with no declaration between them;
  `reportViewport` is only ever referenced at 3085 (decl), 3736 and 3738 (timers).
- **Confidence**: high
- **Suggested action**: delete the block, or move it directly above `reportViewport` (3085)
- **Risk if removed**: none — comment only.

### F4. `data-zcode-shell` marker attributes are never queried (v1 leftover)  [VESTIGIAL]
- **Location**: `android-shell/app/src/main/assets/inject.js:3389` (`style.setAttribute('data-zcode-shell', 'scrollbar-width')`)
  and `android-shell/app/src/main/assets/inject.js:3513` (`el.setAttribute('data-zcode-shell', 'scrollbar')`)
- **Symbol**: the `data-zcode-shell` attribute and its two values
- **Why dead**: nothing reads them — not the page layer, not the bar logic (`barSkippedHere` matches
  `[class*="scrollbar-hide"]`, `[data-zcode-pptx-render-surface]`, `.xterm-viewport`, never this), not Kotlin, not the
  tools. The `'scrollbar-width'` value is inherited verbatim from the reverted v1: `git show 43c6998` shows the removed
  v1 installer already did `style.setAttribute('data-zcode-shell', 'scrollbar-width')`, and plan A (`18968b1`) re-added it
  unchanged — i.e. this is exactly the "unused style-node id" class of leftover the orchestrator asked about. It is
  harmless: it is a write-only identification marker.
- **Reference search**: `data-zcode-shell` over `android-shell/**` → 3 hits, all in inject.js (3389, 3513 and nothing else);
  `querySelector` / `zcode-shell']` over `android-shell/tools/**` → 0 hits for this attribute; `scrollbar|Scrollbar` over
  `app/src/main/java/**` → only MainActivity.kt:222,238-248 (comments + `isVerticalScrollBarEnabled=false`), no attribute read.
- **Confidence**: high (no code reader) / low value
- **Suggested action**: keep but annotate (they are the only handle for identifying our injected nodes in DevTools/CDP),
  or delete if the marker convention is dropped
- **Risk if removed**: none functionally; `tools/cdp.mjs eval "<expr>"` is a generic `Runtime.evaluate` channel, so an
  operator could still have selected `[data-zcode-shell]` by hand during a debug session — that is the only value lost.

### F5. `__zcodeShellComposeSend` return value is computed and discarded on every path  [WRITE-ONLY]
- **Location**: `android-shell/app/src/main/assets/inject.js:2853-2896` (declaration), caller at `:2904`
- **Symbol**: the return value of `G.__zcodeShellComposeSend` (`'空文本，未发送'` / `'no-composer'` / `'fill-rejected'` /
  `'sent=' + how` / `'error:' + e`)
- **Why dead**: the function's jsdoc (2850-2852) promises "返回一行摘要字符串（原生日志与它对齐看时序）", but its **only**
  caller is `G.__zcodeShellDiag`: `if (cmd.indexOf('compose|') === 0) { G.__zcodeShellComposeSend(cmd.substring(8)); return true; }`
  — the string is dropped and `true` is returned instead. `evaluateJavascript(script, null)` on the Kotlin side
  (`MainActivity.kt:800-806`) discards the result too, so no consumer exists at any layer.
- **Reference search**: `__zcodeShellComposeSend` over `android-shell/**` → 2 hits (declaration 2853, call 2904);
  no hit in `app/src/main/java/**` or `tools/**`. (`evaluateJavascript` payloads that name JS functions were grepped —
  see the export list in "Scope audited".)
- **Confidence**: high
- **Suggested action**: keep but annotate (or `return G.__zcodeShellComposeSend(...)` from `__zcodeShellDiag` so the
  documented contract becomes real)
- **Risk if removed**: none — every exit path already emits the same information through `diag(...)` (2720, 2748, 2860,
  2866, 2889, 2893). This is a documentation/contract mismatch, not a lost signal.

### F6. Subsumed condition in `findSendButton`  [UNREACHABLE]
- **Location**: `android-shell/app/src/main/assets/inject.js:2775`
- **Symbol**: `label.indexOf('发送') >= 0 || label.indexOf('发送消息') >= 0`
- **Why dead**: any label containing `发送消息` also contains `发送`, so the second disjunct can never be the reason the
  branch is taken; the `return el` decision is fully determined by the first. (Same class of "always-false *as a
  discriminator*" code the brief asks for; the surrounding branch itself is live.)
- **Reference search**: `发送消息|'发送'` over `android-shell/**` → 1 hit (inject.js:2775, this line only); the page
  side is not in this repo, so this is a pure string-containment argument, not a reference-count argument.
- **Confidence**: high
- **Suggested action**: delete the `|| label.indexOf('发送消息') >= 0` disjunct
- **Risk if removed**: none — the function is only reachable through the adb `compose|` diagnostic (see
  "Known / intentional"), and `inject.test.js` asserts nothing about it.

## Known / intentional (not a new finding)
- **`SHELL_READ_ONLY` read-only early returns** at inject.js:2995-3005 and 3015-3021 — kept on purpose:
  `docs/18` §2.3 + its note (lines 63-64) state the five sealed mechanisms keep "守卫与那行日志" because the log line
  *is* the evidence that the shell is not touching the page. Re-check: **reason still holds** — README:454 still defines
  the read-only contract, and `inject.test.js:1069/1089` pin it. Only the tails below them (F1, F2) are dead.
- **`installVisibilityHijack()` (245-247) and its boot call (3721)** — the historical hijack implementation was deleted
  (`docs/18` §2.3 line 57) and one log line kept as the "shell is not lying to the page" diagnostic. Re-check:
  **reason still holds** — `SHELL_VISIBILITY_HIJACK` no longer exists anywhere in the tree (grep over `android-shell/**`
  → README:668,826 only), and `inject.test.js:529` asserts the page gets real visibility. The call is a live log emitter,
  not dead wiring.
- **`__zcodeShellProbeComposer` (2717) / `__zcodeShellComposeSend` (2853) + their helpers (`findComposer`,
  `describeElement`, `findSendButton`, `fillComposer`)** — never referenced by name outside inject.js and never sent by
  Kotlin (`MainActivity.runDiagCommand` forwards an arbitrary `diag_cmd` string from an adb Intent extra,
  `MainActivity.kt:754-806`); README:1024 documents them as the "造流三件" manual diagnostics. Re-check: **reason
  partially holds** — README itself now says "`compose` 对 Lexical 编辑器无效，**能用的是 `tools/cdp.mjs`**", and the
  code's own comment (2801-2805) records the failure (`填入=""`), so this ~180-line block is operator-reachable but
  documented as ineffective; it is a candidate for a dedicated removal round, not flatly dead.
- **`l1_test` → `nudgeReconnect` (2931-2934)** — README:1011 documents it as "只读壳下只记一行" and `docs/18` §2.3
  keeps guard + log. Re-check: **reason still holds** (diagnostic-only).
- **`kick_test` / `degrade_test` → `relayKickTest` / `relayDegradeTest` (2935-2940)** — `docs/18` §4 "实验件，接管语义
  复验要用", README:1012. Re-check: **reason still holds.**
- **`bg_redial` / `bg_http` (2907-2922)** — README:1021-1022 marks them "仅取证，生产逻辑不用". Re-check: **holds.**
- **`scrollbar-gutter:auto!important` inside `SCROLLBAR_CSS` (3376)** — the comment at 3359-3374 states it is a
  "gutter 保险… 今天是 no-op" (insurance against the page or engine re-introducing the reserved gutter). Re-check:
  **documented as intentional no-op insurance** — do not report as dead CSS.
- **Section 7 as a whole** — not dead and not superseded: `MainActivity.kt:237-248` explicitly delegates the scrollbar
  entirely to the injected layer (it only sets `isVerticalScrollBarEnabled=false` / `SCROLLBARS_INSIDE_OVERLAY`), and
  README:221 records the three scrollbar versions as history. Re-check: **holds.**

## Suspected but unproven
- **ES5/old-engine defensive fallbacks in this range that cannot execute on the shipped engine**: `scheduleFrame`'s
  `setTimeout` fallback (3429-3434), the "no `WeakSet`" path (3427 with 3637/3650), `watchMedia`'s
  `mq.addListener` branch (3273-3275 — `MediaQueryList.addListener` was removed in Chromium 84), and `fillComposer`'s
  `new G.InputEvent` catch fallbacks (2826-2836). Missing evidence: the repo cannot prove the WebView version on every
  target device from here (inject.js:3295 mentions WebView 154 as an observation, not a requirement), and the codebase
  treats this style as deliberate ("Everything here is defensive", inject.js:32-35) — note the analogous
  `G.MutationObserver` else-branch (3312-3318) is *deliberately* exercised by `inject.test.js:1224`, so these shims are
  a documented design choice rather than an oversight. Do not delete without a device-matrix decision.
- **No other suspects.** Everything else in 2659-3749 was traced to a reader; every observer/listener in the range
  (`document` capture-phase `scroll` at 3699, the two `resize` listeners at 3702 and 3737, the `MutationObserver` at
  3303, the `matchMedia` handlers at 3268-3275) has a live callback body, and none is disconnected/never-triggered.

## Summary
- Findings: **6** (UNREACHABLE 3 — F1, F2, F6; VESTIGIAL 2 — F3, F4; WRITE-ONLY 1 — F5; NO-REFS 0; TEST-ONLY 0;
  SUPERSEDED 0; UNUSED-RES 0; UNUSED-DEP 0)
- Highest-value deletions:
  1. `inject.js:3006-3010` — unreachable `forceReconnect` tail behind the constant-true read-only guard (calls a stub).
  2. `inject.js:3022-3030` — unreachable tail that would fabricate a pair ack and write a `pair_status_query` frame.
  3. `inject.js:3045-3053` — orphaned jsdoc block documenting nothing (the next declaration is `scrollbarReport`).
- Estimated removable lines: ~20 (14 lines of unreachable code in F1+F2, 9 comment lines in F3, 1 disjunct in F6;
  F4's two attribute calls are optional and better kept as debug markers).

## Status verification (2026-09-19)

Verified against the live tree: `inject.js` is now **3721 lines** (HEAD `2c89b88` + uncommitted
档0观测 addition). Original text above is unchanged; line numbers below are current.

| 条目 | 原判定 | 当前状态 | 备注 |
| --- | --- | --- | --- |
| F1 `__zcodeShellSetAppForeground` 死链分支尾（`forceReconnect` 调用） | UNREACHABLE | 已删除 | 只读分支现在以 `healDeadLinkOnResume(silence)` + `reportPageState()` 收尾（闸 `:2972`、调用 `:2979`） |
| F2 同函数活链路死尾（伪造 ack + `pair_status_query`） | UNREACHABLE | 已删除 | 活链路只读分支 `:2987-2993`；无 `lastPairAckAt` 写、无 `pair_status_query`；"自欺"说明注释保留 |
| F3 孤儿 jsdoc（viewport metrics 块） | VESTIGIAL | 已删除（已修复） | 该文档已落到 `reportViewport` 正上方（`:3041-3048`，函数 `:3049`），孤儿块消除 |
| F4 `data-zcode-shell` 两个标记属性 | VESTIGIAL | 仍在（仍死，已按建议加注） | `:3357` / `:3484`；`:3482` 注释明示这是 DevTools 里识别壳注入节点的命名空间（keep-but-annotate） |
| F5 `__zcodeShellComposeSend` 返回值被丢弃 | WRITE-ONLY | 仍在（仍死，已按建议加注） | 唯一调用 `:2880-2882` 仍 `return true`；jsdoc `:2825-2827` 已加"⚠️ 这个返回值今天没有任何消费方"（annotate） |
| F6 `findSendButton` 被蕴含析取项 | UNREACHABLE | 已删除 | `:2745` 留有删除记录注释（`findSendButton` 现 `:2724`） |
| known: `SHELL_READ_ONLY` 只读早退 | 有意保留 | 仍在（活着，按设计） | 两处闸门现为 `:2972` / `:2987`（原 2995/3015） |
| known: `installVisibilityHijack` | 有意保留 | 仍在（活着） | `:245` + boot 调用 `:3694` |
| known: 造流三件（ProbeComposer / ComposeSend 簇） | 操作员可达、文档记为无效 | 仍在（活着，诊断） | `findComposer :2641` … `__zcodeShellComposeSend :2830`，经 `__zcodeShellDiag :2875` 分发；本轮清理未触碰 |
| known: `l1_test` → `nudgeReconnect` | 保留 | 仍在（活着） | `:2908-2910` |
| known: `kick_test`/`degrade_test`、`bg_redial`/`bg_http` | 取证保留 | 仍在（活着） | `:2912-2930` 分发齐全 |
| known: `scrollbar-gutter` 保险 CSS | 有意 no-op | 仍在（活着，按设计） | §7 `installOverlayScrollbar :3344` 起 |
| suspected: ES5/旧引擎回退 | 设计取舍 | 仍在（未变） | `scheduleFrame` / `watchMedia` / `fillComposer` 回退原样 |

小结：6 条 finding —— 已删 4（F1/F2/F3/F6，均落在 b627610），仍死 2（F4/F5，两处都已按审计建议
"keep but annotate"处理）。最值得注意：常真闸门之后的两段 UNREACHABLE 死尾（含伪造 pair ack 的
那段）已删净——回前台死链路径现在直接落到 `healDeadLinkOnResume`。
