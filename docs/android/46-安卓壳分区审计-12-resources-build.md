> ⚠️ Historical audit baseline (2026-09-18, pre-cleanup). Status as of 2026-09-19: see "Status verification" section at the end. Many items listed as dead have since been deleted in batches 1–4 (commit b627610).

# resources-build — dead-code findings

Area: `android-shell/app/src/main/res/**`, `AndroidManifest.xml`, `app/build.gradle.kts`,
`settings.gradle.kts`, `gradle.properties`, `app/proguard-rules.pro`.
Categories in play: `UNUSED-RES`, `UNUSED-DEP` (this area has no Kotlin, so no other category applies).

## Scope audited

- `app/src/main/res/values/strings.xml` (100 lines) — 76 user-visible strings (setup / settings / notification channels / shortcuts)
- `app/src/main/res/values/miuix_colors.xml` (38 lines) — 12 MiuiX light colours
- `app/src/main/res/values-night/miuix_colors.xml` (22 lines) — the same 12 names, dark values
- `app/src/main/res/values/miuix_styles.xml` (146 lines) — 11 view styles + 12 dimens (MiuiX metrics, Views port)
- `app/src/main/res/values/themes.xml` (34 lines) — 2 themes (`Theme.ZcodeRemote`, `Theme.ZcodeRemote.Miuix`)
- `app/src/main/res/layout/activity_main.xml` (141 lines) — WebView + setup panel + load-failure panel (ViewBinding)
- `app/src/main/res/layout/activity_settings.xml` (306 lines) — MiuiX settings page (ViewBinding)
- `app/src/main/res/layout/dialog_upload_source.xml` (151 lines) — upload-source chooser dialog (ViewBinding)
- `app/src/main/res/drawable/*.xml` (10 vector/shape files) + `drawable-nodpi/*.png` (2 launcher layers)
- `app/src/main/res/mipmap-anydpi-v26/*.xml` (2 adaptive icons) + `mipmap-xxxhdpi/*.png` (2 legacy icons)
- `app/src/main/res/xml/shortcuts.xml` (36), `xml/file_paths.xml` (6), `xml/data_extraction_rules.xml` (13)
- `app/src/main/AndroidManifest.xml` (134) — 9 permissions, 2 features, 2 activities, 1 service, 2 receivers, 1 provider, 1 meta-data, 1 intent-filter
- `app/build.gradle.kts` (160) — 8 declared dependencies, signing, buildTypes, buildFeatures, packaging, testOptions
- `settings.gradle.kts` (24), `gradle.properties` (27), `app/proguard-rules.pro` (7)
- No `res/anim*`, no `res/raw`, no `res/menu`, no `<bool>`/`<integer>`/`<array>` anywhere.

## Inventory — every definition vs. its references (the negative results, auditable)

Reference corpus = all of `app/src/**` (`.kt`, `.xml`, `.js`, `.json`) = Kotlin, layouts/values, the manifest,
**and** `assets/inject.js` + `assets/zcode-protocol.js` — 0 hits for `getIdentifier(` anywhere in the repo, so
there is no string-name resource lookup to worry about.

Per-name test (one line per definition, machine-run):
`'@'+type+'/'+name+'(?![\w.])|R\.'+type+'\\.'+name+'\b'` (+ `|parent="<name>"` for styles) over the corpus above;
ids additionally tested with `@\+id/<name>|@id/<name>|R\.id\.<name>|binding\.<name>`.

| Definition site | Kind | Defined | Referenced | Zero-reference |
| --- | --- | --- | --- | --- |
| `values/strings.xml` | string | 76 | 76 | — |
| `values/miuix_colors.xml` | color | 12 | 12 | — |
| `values-night/miuix_colors.xml` | color | 12 | 12 | — (one entry is value-identical to light → F6) |
| `values/miuix_styles.xml` | dimen | 12 | 12 | — |
| `values/miuix_styles.xml` | style | 11 | 11 | — |
| `values/themes.xml` | style | 2 | 2 | — |
| `values*/` | bool/integer/array | 0 | — | — |
| 3 layouts | layout | 3 | 3 | — (0 `@layout/`/`R.layout.` hits **by design**: all three are inflated through ViewBinding, see below) |
| 10 `drawable/*.xml` | drawable | 10 | 10 | — (`ic_stat_zcode` is referenced only from Kotlin: `R.drawable.ic_stat_zcode` ×7 in `notify/Notifier.kt`, not from XML) |
| `drawable-nodpi/*.png` | drawable | 2 | 2 | — (`@drawable/` in both `mipmap-anydpi-v26` icons) |
| `mipmap-anydpi-v26/*.xml` | mipmap | 2 | 2 | — (`@mipmap/` in the manifest) |
| `mipmap-xxxhdpi/*.png` | mipmap | 2 | 2 (by name) | **never selected at runtime → F1** |
| `xml/*.xml` | xml | 3 | 3 | — (`@xml/` in the manifest) |
| all layouts | id | 29 | 28 | **`card` → F2** |

ViewBinding check (the reason `@layout/`/`R.layout.` legitimately shows 0 hits):

| Layout | Inflated at | Binding fields read |
| --- | --- | --- |
| `activity_main.xml` | `MainActivity.kt:166` `ActivityMainBinding.inflate`, `:167` `setContentView(binding.root)` | all 11 ids (`root` `:171,574,575`, `webview` `:189,211,425,455…`, `setupPanel` `:456,475,480,617`, `btnScan` `:177`, `btnPaste` `:178`, `btnManual` `:179`, `btnRetry` `:180`, `btnChangeUrl` `:181`, `btnSettings` `:182`, `errorPanel` `:474,481,486`, `errorText` `:482`) |
| `activity_settings.xml` | `SettingsActivity.kt:56` | all 14 ids (`btnBack` `:69`, `currentUrl` `:71`, `rowChangeUrl` `:75`, `rowReloadWeb` `:79`, `rowNotificationPermission` `:84`, `rowClearNotifications` `:85`, `rowBattery` `:90`, `rowDiagnostics` `:92`, `rowShareLog` `:93`, `rowClearDiagnostics` `:94`, `notificationPermissionState` `:137`, `batteryState` `:146`, `versionText` `:99`, `survivalText` `:120`) |
| `dialog_upload_source.xml` | `UploadSourceDialog.kt:36` | `tileAlbum` `:52`, `tileFile` `:53`, `dialogRoot` `:54` — **`card` never read** |

Belt-and-braces negative check run for context: `python tools/check_resources.py` → `resource references OK: 23 files scanned`
(this validates the *other* direction — no dangling references — and confirms nothing in the tree is misspelled into a
non-existent resource).

## Findings

### F1. Both legacy launcher-icon PNGs are unreachable at `minSdk = 26` — 797 KB of the APK  [UNUSED-RES]
- **Location**: `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`, `app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png`
  (each **398,566 bytes**, MD5 `99953E460C8DA671B4A6DD5DD54C7501` — byte-identical to each other)
- **Symbol**: `@mipmap/ic_launcher`, `@mipmap/ic_launcher_round` (the `mipmap-xxxhdpi` *variants* of both)
- **Why dead**: `minSdk = 26` (`app/build.gradle.kts:36`), and both names also exist as `mipmap-anydpi-v26/*.xml`
  (`ic_launcher.xml:12-13`, `ic_launcher_round.xml:4-5`). `anydpi` (AOSP `DENSITY_ANY`) outranks **every** specific
  density bucket in resource resolution, and the `-v26` qualifier is satisfied on every device the app can be
  installed on, so the adaptive-icon XML is always the selected variant and the PNG variants are never loaded.
  They are also duplicates of each other, so the "round" file carries no distinct artwork either. They are packaged
  because `isShrinkResources = false` (`app/build.gradle.kts:96`) and R8 is off.
- **Reference search**: `@mipmap/ic_launcher|@mipmap/ic_launcher_round` over `android-shell/app/src` →
  exactly 2 hits, `AndroidManifest.xml:47` (`android:icon`) and `:49` (`android:roundIcon`) — these are the
  *by-name* references that resolve to the `anydpi-v26` XML, they do not select the density bucket.
  `R\.mipmap\.` over all `*.kt` → 0 hits; `getIdentifier(` over the whole repo → 0 hits; `getDrawableForDensity|AssetManager`
  → 0 hits. So nothing can force the xxxhdpi variant.
- **Confidence**: high on the mechanism (AOSP `ResTable_config::isBetterThan` prefers `DENSITY_ANY`; the adaptive-icon
  docs state a legacy icon is only needed below API 26). Not device-verified — no build/emulator run is possible here.
  Verify with `aapt2 dump resources` on the built APK, or by deleting them and installing on one HDPI and one XXXHDPI device.
- **Suggested action**: delete both PNGs (keep `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml`).
- **Risk if removed**: none at `minSdk 26`; `android:icon`/`android:roundIcon` keep resolving, the launcher applies its
  own mask, and nothing in `res/` or Kotlin references the PNGs by path. Only a future drop of `minSdk` below 26 would
  require re-adding a legacy set. Payoff: ~778 KB off a **6.32 MB** APK (`android-shell/zcode-remote.apk`), i.e. ~12%.

### F2. `@+id/card` in the upload dialog has no consumer  [UNUSED-RES]
- **Location**: `app/src/main/res/layout/dialog_upload_source.xml:24` (`android:id="@+id/card"`, the `MaterialCardView`)
- **Symbol**: `card`
- **Why dead**: the id is never used, in XML or in Kotlin. The card's constraints are all against `parent`
  (`:32-37`), its children never reference `@id/card`, and `UploadSourceDialog` only wires `tileAlbum`, `tileFile`
  and `dialogRoot` (`UploadSourceDialog.kt:52-54`). ViewBinding still generates a `DialogUploadSourceBinding.card`
  field (`UploadSourceDialog.kt:36`), so it is a generated-but-never-read member.
- **Reference search**: `@id/card|@\+id/card|R\.id\.card` over `android-shell/app/src/main/res` → **1 hit, the
  declaration itself**; `R\.id\.|findViewById` over all of `app/src/**/*.kt` → **0 hits** (the app is 100% ViewBinding),
  so the only possible consumer would be `binding.card`, and `binding\.card\b` → 0 hits.
- **Confidence**: high.
- **Suggested action**: delete the `android:id` attribute.
- **Risk if removed**: none — no test pins layout ids, and `tools/check_resources.py` only rejects *dangling*
  references, so removing a definition cannot break it.

### F3. `res/xml/shortcuts.xml` hard-codes `targetPackage`, which is wrong for the debug variant  [UNUSED-DEP]
- **Location**: `app/src/main/res/xml/shortcuts.xml:22` and `:34` (`android:targetPackage="com.zcode.remote"`)
- **Symbol**: the `android:targetPackage` attribute of both `<intent>` elements (`shortcut_scan`, `shortcut_settings`)
- **Why dead**: `app/build.gradle.kts:103` sets `applicationIdSuffix = ".debug"`, so the debug applicationId is
  `com.zcode.remote.debug` while the shortcut intents name `com.zcode.remote`. The two shortcut actions are handled
  **only** by `MainActivity` (`MainActivity.kt:680` `ACTION_SCAN`, `:691` `ACTION_OPEN_SETTINGS`), which in a debug
  build is installed under the `.debug` package → the launcher's long-press entries point at a package that is not
  installed and cannot resolve there. Release builds are unaffected. The attribute is also redundant in principle:
  a static shortcut intent is implicitly scoped to the declaring package.
- **Reference search**: `targetPackage` over `android-shell/app/src` → 2 hits, both declarations.
  `com\.zcode\.remote\.debug|applicationIdSuffix` over `android-shell` → 1 hit, `app/build.gradle.kts:103`.
  `action\.SCAN|action\.OPEN_SETTINGS` → `shortcuts.xml:20,32` + `MainActivity.kt:945,948,680,691`.
- **Confidence**: medium — the mismatch itself is proven; what the platform does with a non-matching
  `targetPackage` (silent failure vs. falling back to the declaring package) is behaviour I could not test
  (Gradle and a device are both out of reach in this sandbox), and manifest placeholders are **not** substituted
  inside `res/xml/*`, so `${applicationId}` is not an option here.
- **Suggested action**: investigate further on a debug install; most likely fix is to drop `android:targetPackage`
  and keep `android:targetClass`.
- **Risk if removed**: if the platform does **not** default the package to the declaring app, the shortcuts would
  break in release too — check one shortcut launch on each variant before landing.

### F4. Backup config: `android:fullBackupContent="false"` guards nothing, and only the `<device-transfer>` half of `data_extraction_rules.xml` does work  [UNUSED-DEP]
- **Location**: `app/src/main/AndroidManifest.xml:44-46` (`allowBackup="false"`, `dataExtractionRules=…`, `fullBackupContent="false"`),
  `app/src/main/res/xml/data_extraction_rules.xml:5-8` (`<cloud-backup>` block) vs `:9-12` (`<device-transfer>` block)
- **Symbol**: the `android:fullBackupContent` attribute, and the `<cloud-backup>` exclusion block
- **Why dead**: on Android 12+ (API 31+, `targetSdk 35`) the documented behaviour change is that
  `allowBackup="false"` disables **cloud backup** but *not* device-to-device transfer — which is exactly why the
  `<device-transfer>` excludes are load-bearing and must stay. Consequences: (a) the `<cloud-backup>` excludes
  guard a path `allowBackup="false"` has already closed; (b) `android:fullBackupContent` is only read on API ≤ 30,
  where `allowBackup="false"` already opts the app out entirely, and is superseded by `dataExtractionRules` on 31+
  → inert on every API level this app supports (`minSdk 26`).
- **Reference search**: `fullBackupContent|allowBackup|dataExtractionRules` over `android-shell/app/src` →
  the three manifest lines only; the rules file itself is referenced from `AndroidManifest.xml:45` and nowhere else.
  What the excludes are protecting (verified so it is clear which half matters): the sid/hash-bearing URL lives in
  SharedPreferences (`Prefs.kt:19` `remoteUrl`, written at `MainActivity.kt:453`) → `domain="sharedpref"` is live;
  the rolling log lives in `getExternalFilesDir()/logs` (`ShellLog.kt:55-60`) → `domain="file"` covers only its
  `filesDir` fallback, and the credential is redacted before logging anyway (`RemoteUrl.kt:64` `?<凭据已隐藏>`
  via `MainActivity.kt:454`).
- **Confidence**: medium-high (documentation-based; the Android 12 behaviour change is quoted in the linked page).
- **Suggested action**: **keep but annotate** — add to `data_extraction_rules.xml` which half is live
  (`device-transfer` yes, `cloud-backup` covered by `allowBackup=false`) and mark `fullBackupContent="false"` as the
  pre-31 belt-and-braces. Do not delete the `<device-transfer>` block, and do not delete the file.
- **Risk if removed**: deleting the file or flipping `allowBackup` later would silently start shipping the
  credential-bearing SharedPreferences to a new device; deleting only `fullBackupContent` is behaviour-neutral today
  but removes the explicit pre-31 opt-out.

### F5. Four manifest/shortcut attributes restate the platform default  [UNUSED-DEP]
- **Location**: `AndroidManifest.xml:58` `android:hardwareAccelerated="true"`, `:50` `android:supportsRtl="false"`,
  `:51` `android:usesCleartextTraffic="false"`, `res/xml/shortcuts.xml:14,26` `android:enabled="true"`
- **Symbol**: those four attributes
- **Why dead**: each is already the platform default for this app's configuration — `hardwareAccelerated` defaults to
  true from API 14 on (minSdk 26); `supportsRtl` defaults to false; `usesCleartextTraffic` defaults to false for
  `targetSdk ≥ 28` (this app is 35); `android:enabled` on `<shortcut>` defaults to true. Grep confirms each is
  declared exactly once and never read or overridden anywhere.
- **Reference search**: `hardwareAccelerated|supportsRtl|usesCleartextTraffic` over `android-shell/app` → 1 hit each
  (the declaration); `android:enabled` → 2 hits, both in `shortcuts.xml`. Nothing under `tools/` or the test sources
  asserts on them.
- **Confidence**: high on the defaults; low value (they do document intent, and `usesCleartextTraffic="false"` is
  security-relevant to a reader).
- **Suggested action**: keep but annotate, or delete — cosmetic either way. Do **not** treat
  `allowBackup`/`fullBackupContent` in the same way (see F4), they are not defaults.
- **Risk if removed**: none (defaults identical). Note `android:hardwareAccelerated` removal has no effect on the
  WebView, which is always hardware accelerated.

### F6. Two definitions that have no effect at all  [UNUSED-RES]
- **Location**: (a) `app/src/main/res/values/miuix_styles.xml:15` `<resources xmlns:app="http://schemas.android.com/apk/res-auto">`;
  (b) `app/src/main/res/values-night/miuix_colors.xml:8` `<color name="miuix_on_primary">#FFFFFFFF</color>`
- **Symbol**: the `app` XML namespace declaration; the night override of `miuix_on_primary`
- **Why dead**: (a) no `app:`-prefixed attribute is used anywhere in that file — the only other occurrence of `app:`
  is inside the comment at `:129` explaining why library attributes are deliberately kept out of these styles;
  (b) the night value is byte-identical to the light one (`values/miuix_colors.xml:21` `#FFFFFFFF`), so the
  `values-night` qualifier changes nothing for that colour — the other 11 night colours all differ and are live.
- **Reference search**: `app:` over `miuix_styles.xml` → 2 hits, one declaration + one comment (line 129);
  `miuix_on_primary` → declared at `values/miuix_colors.xml:21`, re-declared at `values-night/miuix_colors.xml:8`,
  used once at `values/themes.xml:30` (`colorOnPrimary` of the live Miuix theme).
- **Confidence**: high.
- **Suggested action**: delete both lines (or keep the night line as a verbatim transcription of MiuiX
  `darkColorScheme()`, which is what the file header claims it is — annotate if so).
- **Risk if removed**: none. The `values-night` **file** must stay: 11 of its 12 colours differ and are referenced.

### F7. `ic_back.xml`'s built-in tint is overridden at its only use site  [UNUSED-RES]
- **Location**: `app/src/main/res/drawable/ic_back.xml:6` `android:tint="?attr/colorControlNormal"`,
  overridden at `app/src/main/res/layout/activity_settings.xml:24` `app:tint="@color/miuix_on_background"`
- **Symbol**: the `android:tint` attribute of the `ic_back` vector
- **Why dead**: `ImageView`'s `setImageTintList` (what `app:tint` drives) calls `Drawable.setTintList` on the
  drawable, replacing the vector's own tint list — so at the single use site the `?attr/colorControlNormal` value
  never renders. Only one consumer exists for this drawable.
- **Reference search**: `@drawable/ic_back|R\.drawable\.ic_back` over `android-shell/app/src` → 1 hit
  (`activity_settings.xml:23`), whose `app:tint` is on the next line. `?attr/colorControlNormal` → 1 hit, this
  declaration (nothing else in the app reads that attribute).
- **Confidence**: medium (tint precedence is framework code I read, not something I ran).
- **Suggested action**: keep but annotate, or delete the attribute — **but** note the vector's fill is
  `#FFFFFFFF`, so an untinted future use would draw white; deleting is only safe while the single use site keeps
  its `app:tint`.
- **Risk if removed**: none today; a latent white-icon risk if the drawable is reused untinted later.

## Verified live — explicitly checked, no finding

These were the plausible candidates; each was traced to a real consumer, so reporting them would be a false positive:

| Candidate | Verdict / evidence |
| --- | --- |
| `android.permission.ACCESS_NETWORK_STATE` (`AndroidManifest.xml:6`) | **Not reported.** No app code touches `ConnectivityManager`/`NetworkCapabilities` (`^import ` sweep of all 32 Kotlin files + `ConnectivityManager` repo-wide → 0 hits). But it has one plausible indirect consumer: Chromium's in-process network-change notifier, i.e. `navigator.onLine`, which the injected layer actually reads at `assets/inject.js:2587` (`' online=' + navigator.onLine`). Chromium degrades gracefully (it catches `SecurityException`), so its necessity cannot be proven or disproven without a device test → see *Suspected*. Every other permission in the file has a justification comment; this one has none, which is why it was checked hardest. |
| `WAKE_LOCK` (`:10`) | live — `ShellRuntime.kt:1007-1029` (`carrierWakeLock`, `PARTIAL_WAKE_LOCK` at `:1014`). |
| `CAMERA` (`:13`) | live — zxing `ScanContract.launch` at `MainActivity.kt:501` (`startScan()`); `CaptureActivity` raises the runtime request. |
| `POST_PROMOTED_NOTIFICATIONS` (`:19`) | live — `LiveUpdate.requestPromotion` called from `Notifier.kt:308,409`; the permission is listed as a promotion precondition at `LiveUpdate.kt:46`. |
| `POST_NOTIFICATIONS` (`:22`) | live — `MainActivity.kt:646,651`. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` (`:27,28`) | live — `KeepAliveService.kt:40,58` (`ServiceCompat.startForeground` + `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`), matching `foregroundServiceType="specialUse"` (`:89`) and the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property (`:91-92`). |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (`:32`) | live — `SettingsActivity.kt:180,187`. |
| `<uses-feature camera / camera.autofocus required="false">` (`:35-40`) | intentional: they *prevent* the `CAMERA` permission from implying `android.hardware.camera` as required, which is what keeps camera-less tablets installable. |
| `INTERNET` (`:5`) | live — the WebView loads `zcode.z.ai/remote/v4`. |
| `dataExtractionRules` / `<device-transfer>` | live and load-bearing — see F4. |
| `DiagReceiver` + `ACTION_DIAG` filter (`:101-108`) | live — `DiagReceiver.kt:32` reads `EXTRA_DIAG_CMD`; also handled at `MainActivity.kt:669-673`. |
| `CardDismissReceiver` (`:117-118`) | live — `Notifier.kt:429-432` builds its `PendingIntent` (`ACTION_DISMISS`, `EXTRA_ID`). |
| `FileProvider` + `@xml/file_paths` (`:123-129`) | live — authority `${applicationId}.fileprovider` matches `SettingsActivity.kt:259`; `<cache-path name="share" path="share/">` matches `ShellLog.kt:124` (`File(context.cacheDir, "share")`); `FLAG_GRANT_READ_URI_PERMISSION` set at `SettingsActivity.kt:264`. |
| `@xml/shortcuts` + both shortcut definitions (`:70-72`) | live — `MainActivity.handleIntent` handles both actions (`:680`, `:691`); only `targetPackage` is flagged (F3). |
| `android:parentActivityName` (`:79`) | **Suspected, not a finding:** with `Theme.Material3.DayNight.NoActionBar` there is no Up affordance and nothing calls `NavUtils`/`getParentActivityIntent`; `btnBack` just calls `finish()` (`SettingsActivity.kt:69`). It is harmless metadata the task stack already satisfies. |
| All 8 `implementation`/`testImplementation` deps except one | live — see F-list note below: `core-ktx` (`androidx.core.*` imports), `appcompat` (`AppCompatActivity`/`AppCompatDialog`/`AlertDialog`), `activity-ktx` (`addCallback`, `PickVisualMediaRequest`, `ActivityResultContracts`, `enableEdgeToEdge`), `constraintlayout` (only from XML: `ConstraintLayout` root + `app:layout_constraint*` in `dialog_upload_source.xml`), `material` (theme parents `themes.xml:16`, `Widget.Material3.Button.*` at `activity_main.xml:80,89,127,135`, `MaterialButton`/`MaterialCardView`, `DynamicColors` at `ZcodeRemoteApp.kt:6-7`), `webkit` (`WebViewCompat`/`WebViewFeature`, `MainActivity.kt:36-37`), `zxing-android-embedded` (`ScanContract`/`ScanOptions`, `:38-39`), `okhttp` (`NativeRelayClient.kt:3-7`), `junit` + `org.json:json` (test imports). |
| `settings.gradle.kts`, `gradle.properties` | no dead flags found. `zcodeBaseVersion` (`gradle.properties:27`) is read at `app/build.gradle.kts:54` and documented in `README.md:287` / `CHANGELOG.md:5`; `android.useAndroidX` / `android.nonTransitiveRClass` are required; the `org.gradle.*` flags are all effective. `settings.gradle.kts`'s two repository blocks are both used (plugin resolution vs. dependency resolution, `FAIL_ON_PROJECT_REPOS`). |
| `app/build.gradle.kts` signing/version/packaging blocks | live: `key.properties` + `ANDROID_*` (`:19-28`), the release→debug signing fallback (`:85-94`), `viewBinding` (`:118`, consumed by all three layouts), `buildConfig` (`:122`, consumed at `MainActivity.kt:227` `BuildConfig.DEBUG`), `testOptions.unitTests.isReturnDefaultValues` (`:134`, needed by `UploadMimeTest` → `UploadMime.kt:3` `android.webkit.MimeTypeMap`). |
| `app/proguard-rules.pro` | not dead — see *Known / intentional*. |
| `miuix_colors.xml` (both qualifiers), all 12 dimens, all 11 MiuiX styles, both themes | all have ≥1 live reference (see the inventory table); the previous round's 9-colour / `MiuixRowValue` / `Theme.ZcodeRemote.Splash` deletions left nothing else unreferenced. |

One dependency **is** a genuine `UNUSED-DEP` and is folded into F5's family rather than given its own finding
number, because the fix is a one-line deletion with a version-resolution caveat:

- **`androidx.lifecycle:lifecycle-runtime-ktx:2.8.7`** (`app/build.gradle.kts:142`) — **zero imports**:
  `^import ` sweep of all 32 files under `app/src/main/java/**` plus the 11 test files → no `androidx.lifecycle.*`
  import; `lifecycle|Lifecycle|lifecycleScope|repeatOnLifecycle` over `android-shell/**/*.kt` → 3 hits, **all
  inside comments** (`ShellRuntime.kt:1490,1825`, `ZcodeRemoteApp.kt:46` `registerActivityLifecycleCallbacks`,
  which is `android.app.Application`, not lifecycle-runtime). `activity-ktx` already pulls `lifecycle-runtime`
  transitively, so nothing compiles against the direct dependency. Caveat: the direct line also acts as a
  version pin (2.8.7 over activity-ktx 1.9.3's transitive lifecycle), so removal is behaviour-neutral today but
  would change the resolved version if a transitive consumer ever appears. Confidence high on "no code uses it",
  medium on "safe to delete"; suggested action: delete, keeping the version pin only if a future bump needs it.

## Known / intentional (not a new finding)

- `app/proguard-rules.pro:5-7` — `-keepclassmembers class com.zcode.remote.** { @android.webkit.JavascriptInterface <methods>; }`.
  Documented in the file's own header (`:1-2`) as inert because `isMinifyEnabled = false`
  (`app/build.gradle.kts:95`). Re-check: **reason still holds, and the rule is not dead-by-definition** — it is
  evidence of *live* reflection, not of dead code: `WebAppBridge.kt:22,41` has exactly two
  `@JavascriptInterface` methods (`postMessage`, `config`) that the injected layer calls by string name through
  `window.ZCodeShell` (`MainActivity.kt:226` `addJavascriptInterface(WebAppBridge(prefs), BRIDGE_NAME)`,
  `BRIDGE_NAME = "ZCodeShell"` at `:956`). Keep the rule; the day R8 is switched on it is required.
- `res/xml/data_extraction_rules.xml` `<device-transfer>` block — deliberately kept and load-bearing (F4).
- `docs/18-全仓审计-错误路线残留与清理.md` §4/§5 — **nothing in this area**. §4's kept list is entirely Kotlin/JS
  (`RelayBridge`, `TaskStore`, `RelayWire.METHOD_ROWS_RANGE`, `carrier_*`, `nudge*`), and §5's deferred items are
  `Prefs.subscribeAllWorkspaces`, the inject.js watchdog block, `zcode-protocol.js` self-subscribe and local junk.
  None of them touches resources, the manifest or the build files. §2.2 already removed the resource files this
  round would otherwise have re-found (whole-file `values/colors.xml`, `values/styles.xml`, 9 colours × 2
  qualifiers, 1 style, 1 splash theme) — re-verified as still absent.

## Suspected but unproven

- `android.permission.ACCESS_NETWORK_STATE` (`AndroidManifest.xml:6`) — missing evidence: no in-repo reader exists,
  so the only argument for keeping it is Chromium's in-process network-change notifier, which the injected layer
  depends on for its `online=` diagnostic (`assets/inject.js:2587`). Chromium catches the `SecurityException` when
  the permission is absent, so "useless" vs. "makes `navigator.onLine` actually track the network" cannot be
  separated without a device test. To settle it: remove the line, run on a device, toggle airplane mode, and see
  whether the `online=` field in the diagnostics snapshot flips (and whether the page's own online/offline handling
  reacts). If it does not, it is a clean `UNUSED-DEP` deletion.
- `android:parentActivityName=".MainActivity"` (`AndroidManifest.xml:79`) — missing evidence: no `NavUtils` /
  `getParentActivityIntent()` / Up-affordance consumer found under a `NoActionBar` theme, so it looks inert; but
  the task-stack semantics it declares are also what makes `finish()` land back on MainActivity, so it is at worst
  documentation. Not worth a deletion on its own.
- `res/xml/data_extraction_rules.xml:6-7` (`<cloud-backup>` excludes) — missing evidence: whether `allowBackup="false"`
  is honoured identically by every OEM migration tool. The doc-based reading is that the block is redundant
  (see F4); some vendors' D2D implementations have historically ignored `allowBackup`. Recommend annotating, not deleting.
- `packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }`
  (`app/build.gradle.kts:125-131`) — missing evidence: whether any of the 8 declared artifacts actually ships those
  files today. A duplicate-resource packaging conflict is the usual reason for this block, and proving "no conflict"
  requires running Gradle (`:app:assembleRelease`), which this sandbox cannot do. Harmless either way.
- `android:configChanges="…|density|uiMode|keyboardHidden|screenLayout|smallestScreenSize"` (`AndroidManifest.xml:56`)
  with **no** `onConfigurationChanged` override in `MainActivity` — missing evidence: whether the WebView-only screen
  needs any of them re-applied. Not dead code, but if the page-bar strip ever looks stale after a dark-mode switch it
  is the first place to look; it needs a device to confirm.

## Summary

- Findings: **7** — `UNUSED-RES` 4 (F1, F2, F6, F7), `UNUSED-DEP` 3 (F3, F4, F5).
- Also in the `UNUSED-DEP` family but not given its own number (folded into the live-verification table):
  `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7` (`app/build.gradle.kts:142`) — zero imports anywhere.
- Highest-value deletions:
  1. `res/mipmap-xxxhdpi/ic_launcher.png` + `ic_launcher_round.png` — **~778 KB (~12%) off a 6.32 MB APK**, never
     selected at `minSdk 26` (F1).
  2. `app/build.gradle.kts:142` `lifecycle-runtime-ktx` — the only dependency with zero imports in the project.
  3. `res/layout/dialog_upload_source.xml:24` `@+id/card` — dead id feeding a dead ViewBinding field (F2), plus
     `res/xml/shortcuts.xml:22,34` `targetPackage` (F3), which silently breaks both launcher shortcuts on debug builds.
- Estimated removable lines: **~11 lines of XML/Gradle + 797,132 bytes (2 binary files)**. Conservative on purpose:
  F4/F5/F7 recommend *annotate* rather than delete, so most of the remaining findings are documentation work.
- Negative results worth carrying forward: **every one of the 125 value-level definitions (76 strings, 24 colours,
  12 dimens, 13 styles) and all 22 file-based resources (3 layouts, 12 drawables, 4 mipmaps, 3 xml) has at least one
  live reference except those listed above.** No dead string, no dead colour (the 2026-09-16 cull is complete and
  correct), no dead style, no dead dimen, no dead layout, no dead drawable, no dead manifest component or permission
  beyond the ACCESS_NETWORK_STATE caveat.
- Files checked and found clean (no dead code at all): `res/values/strings.xml`, `res/values/miuix_colors.xml`,
  `res/values-night/miuix_colors.xml`, `res/xml/file_paths.xml`, `settings.gradle.kts`, `app/proguard-rules.pro`.

---

## Status verification (2026-09-19)

Re-verified by grep over `app/src` + `tools/` only. All four cleanup batches landed in commit `b627610` (2026-09-18); batch markers below cite the commit message (第 1 批 = docs/lint/快捷方式, 第 2 批 = 机械清理 + 图标).

| 条目 | 原判定 | 当前状态 | 备注 |
| --- | --- | --- | --- |
| F1 `mipmap-xxxhdpi/ic_launcher{,_round}.png` | UNUSED-RES | **已删除** | 目录已不存在（b627610 第 2 批，commit message 实测 APK −702 KB / −10.8%）。自适应图标同时从 `mipmap-anydpi-v26/` 挪到 `mipmap-anydpi/`（minSdk 26 下 `-v26` 限定符也省了） |
| F2 `dialog_upload_source.xml` 的 `@+id/card` | UNUSED-RES | **仍在（仍死）** | `dialog_upload_source.xml:24` 仍声明；`UploadSourceDialog.kt:52-54` 仍只接 `tileAlbum`/`tileFile`/`dialogRoot`，`binding.card` 仍无读点 |
| F3 `shortcuts.xml` 的 `targetPackage` 指错 debug 包 | UNUSED-DEP | **已解决（换了一种修法）** | b627610 第 1 批未删属性，而是新增 `app/src/debug/res/xml/shortcuts.xml`（`targetPackage="com.zcode.remote.debug"`，:33/:45）；main 的 `shortcuts.xml:22/:34` 保持 `com.zcode.remote` 不动 |
| F4 `fullBackupContent="false"` + `<cloud-backup>` 排除 | UNUSED-DEP | **仍在（未动）** | `AndroidManifest.xml:44-46` 与 `data_extraction_rules.xml:5-12`（cloud-backup + device-transfer 两块都在）原样；原建议本是"annotate"，尚未落注 |
| F5 四个复述平台默认值的属性 | UNUSED-DEP | **仍在（仍死）** | `AndroidManifest.xml:50/51/58`（supportsRtl / usesCleartextTraffic / hardwareAccelerated）与 `shortcuts.xml` 的 `android:enabled="true"`（main `:14/:26` + debug 副本 `:25/:37`）均未删 |
| F6(a) `miuix_styles.xml` 的 `xmlns:app` | UNUSED-RES | **仍在（仍死）** | 第 1 批把样式按系统版本拆成 3 份后，`xmlns:app` 声明反而变成 3 处（`values/miuix_styles.xml:20`、`values-v28:13`、`values-v31:12`）；三个文件仍无 `app:` 前缀属性（仅 `values:137` 注释提及） |
| F6(b) `values-night` 的 `miuix_on_primary` | UNUSED-RES | **仍在（仍死）** | `values-night/miuix_colors.xml:8` 仍为 `#FFFFFFFF`，与 light（`values/miuix_colors.xml:21`）逐字节相同；`themes.xml:30` 仍只引用该名字 |
| F7 `ic_back.xml` 的 `android:tint` | UNUSED-RES | **仍在（仍死）** | `ic_back.xml:6` 仍带内置 tint；唯一使用点 `activity_settings.xml:24` 仍以 `app:tint="@color/miuix_on_background"` 覆盖 |
| 附加：`androidx.lifecycle:lifecycle-runtime-ktx:2.8.7` | UNUSED-DEP（折入正文） | **已删除** | `app/build.gradle.kts:142-143` 现为注释：「Removed as unused (zero androidx.lifecycle imports in main/ + test/), but it also pinned activity-ktx's transitive lifecycle-runtime: re-add if needed.」——版本钉子的提醒也留了 |
