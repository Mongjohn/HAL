# Technical findings

Historical audit baseline: `a006420`. For subsequent approved source changes, the debug installation and remaining verification gates, see [implementation status](IMPLEMENTATION_STATUS.md).

Baseline `a006420`, 2026-11-25. Paths below are relative to `app/src/main/java/com/evofactory/hal/`, except manifests/build files. Line numbers are one-based at that baseline. **Confirmed** establishes code behavior, not measured impact. **Suspected** needs runtime evidence. **Recommendation** is an improvement opportunity rather than an established defect. **Measured** is reserved for actual observed metrics in [05](05_PERFORMANCE_AUDIT.md).

## Finding register

| ID | Severity | Evidence | Finding | Confidence |
| --- | --- | --- | --- | --- |
| HAL-01 | P1 | Confirmed | Optional snapshot IO can crash HAL; writes/pruning have no serialized ownership | High |
| HAL-02 | P1 | Confirmed | Invalid persisted camera JSON can prevent launcher startup | High |
| HAL-03 | P2 | Confirmed | Resume-driven app/provider refresh and missing tile invalidation | High |
| HAL-04 | P2 | Confirmed | Media artwork request/decode ownership is duplicated per rendered reading | High |
| HAL-05 | P2 | Confirmed | Every preference write invalidates the dream's full configuration projection | High |
| HAL-06 | P2 | Confirmed | Forecast subscriptions ignore actual forecast-display demand | High |
| HAL-07 | P2 | Confirmed main-thread execution; suspected latency | Large editor transformations run on the remote-input thread | High for path; medium for impact |
| HAL-08 | P2 | Suspected overload impact; confirmed unbounded queue | HA socket events have no backlog bound | Medium |
| HAL-09 | P2 | Confirmed | Frigate thumbnails have count bounds but no decoded-byte/dimension bounds | High |
| HAL-10 | P2 | Confirmed state gap; suspected stalled-source manifestation | Video first-frame watchdog does not reset readiness for normal item transitions | High for state; medium for device symptom |
| HAL-11 | P2 | Confirmed execution; suspected latency | Configuration parsing/mirroring spans startup and independent hosts | High for ownership; medium for impact |
| HAL-12 | P2 | Confirmed measurement gap / Recommendation | No reproducible app-specific startup/input/release benchmark baseline | High |
| HAL-13 | P2 | Confirmed | Retained-log redaction is not applied to logcat | High |
| HAL-14 | P4 | Recommendation | Verify backup, production shrinking/profiles and small obsolete assets/permissions | Medium |

No P0 was established. Severity reflects supported impact, not class size or deviation from a preferred framework.

## HAL-01 — Snapshot writes must not terminate the launcher

- **Evidence:** `CameraView.kt:1069`, `SnapshotStore.save`, sets `saved = current` then starts a raw `thread`; at line 1074, opening/compressing/writing the file and setting its timestamp have no catch or atomic replacement. Pruning at line 988 starts a separate raw thread. `AppLog.kt:49` installs the process uncaught-exception handler and delegates to Android's previous handler.
- **Root cause / capability:** optional camera persistence has no single failure-contained writer owner. Capture/display uses a shared snapshot map, but saving/pruning run independently. Periodic captures and release call save; write failures can escape and terminate the process. An interrupted write can leave a partial JPEG. Marking `saved` before success also suppresses retry for that exact snapshot.
- **Affected path / consequences:** live camera → good PixelCopy frame → snapshot save, or camera release → save. Low storage or inaccessible storage can make the optional camera take down app launching. Concurrent old/new saves can overwrite newer data; pruning may race a late save. No crash or race was injected on the device in this audit.
- **Solution / alternatives:** a bounded serial latest-wins writer keyed by camera identity, atomic replacement, expected IO failure reporting and generation-aware pruning. Keep previous snapshot on failure. Merely adding a catch is a useful first isolated fix but leaves ordering/partial-file problems. Do not hold the UI thread while compressing or clear last-good images on hide.
- **Benefit / complexity / dependencies:** high reliability benefit; medium complexity; independent of catalog/config refactors. Capture remains separate from persistence; no generic storage framework required.
- **Risks / verification:** failing/slow writes, repeated save ordering, prune during write, interrupted replace, disk-full, process restart and saved timestamp must be tested with a controllable filesystem/writer seam. Verify HAL remains navigable and only newest valid image persists. Confirm PixelCopy/stream visuals on SHIELD. Confidence high.

## HAL-02 — Corrupt camera settings cross the startup boundary

- **Evidence:** `LauncherPrefs.kt:129` directly calls `camerasFromJson`; `Cameras.kt:98` uses `JSONArray` and required `getString` fields without recovery; `MainActivity.kt:142` reads `prefs.cameras` synchronously before `setContent`. `Screensaver.kt` also reads saved cameras for the camera dream. By contrast, installed-app/layout/video decoders already use guarded reads.
- **Root cause / affected features:** optional camera configuration decoding is treated as trusted initialization. Malformed persisted JSON or a missing required field throws before a usable launcher screen. The Home-helper crash guard can eventually fall back to the OEM launcher, but that does not repair HAL's configuration.
- **Solution / alternatives:** validate/recover the camera section at the persistence boundary, retain the invalid raw value for repair and present a usable launcher plus a recoverable settings error. Keep old single-camera migration. A broad catch around Activity creation would conceal unrelated failures; resetting all preferences would lose customization.
- **Benefits / cost:** isolates optional integration corruption; low-to-medium complexity, no architectural prerequisite.
- **Risk / tests:** test malformed JSON, wrong shapes, missing required fields, valid legacy camera and saved credentials; opening editor/replacing the damaged section must work without automatic overwrite. Device process-death/relaunch test should preserve unrelated settings. Confirmed path, high confidence; no assertion that valid current saves normally create corruption.

## HAL-03 — App and provider data need explicit invalidation ownership

- **Evidence:** `MainActivity.kt:162–173` runs `loadInstalledApps` and `refreshWatchNext` after every resume frame. `InstalledApps.kt:52` queries both launcher categories/labels; line 78 caches 100 images by package only, with no invalidation API. `AppRow.kt:126` keys image production only by `app.key`. `MainActivity.kt:846` only gates Watch Next on permission/resume, whereas `ArtworkScreensaver.kt:148–166` gates it on enabled media-art demand. No package-change receiver was found in the application source/manifest.
- **Root cause / overlap:** app discovery, saved metadata, pictures and artwork recommendations were added at different times without shared freshness policy. There is one discovery implementation, not two competing PackageManager libraries. Activity/dream separately own equivalent provider snapshots and may query during overlap; they legitimately differ in lifecycle.
- **Consequence / cost:** two full PM queries plus labels/sorting per return, provider query even with artwork disabled/local, stale package artwork after app update, delayed installed-list updates while HAL stays visible. Actual latency and query frequency during user navigation are unmeasured. App cache entry count is bounded; it is not an unbounded cache.
- **Solution / migration:** introduce the small catalog owner in [03](03_DUPLICATION_AND_CONSOLIDATION.md); reuse query/filter functions, version images, deduplicate concurrent misses, make provider observation demand-driven. Seed cached app labels immediately, reconcile after missed hidden events and preserve user order/hidden lists separately. Migrate Activity, Apps/drawer/editor and dream, then remove their duplicated refresh state.
- **Alternatives / trade-offs:** keep resume reconciliation as a fallback but avoid unconditional refresh when known clean; listening only while visible misses changes in other apps. A permanent service is unnecessary. Shared metadata should not make recommendation failures block app launch.
- **Complexity / dependencies / verification:** medium; benefit less work on Home return and correct app updates; regression risks stale/missing apps, enable/disable/update identity, tile focus. Count PM/provider calls for repeated clean returns, update a package, install/remove while hidden, test non-TV apps, cache eviction and failed launch. Depends on baseline HAL-12 for speed claims. High confidence.

## HAL-04 — Reuse media image work across renderers

- **Evidence:** `HaMediaPlayer.kt:134–148`, `MediaArtwork`, creates its own producer, calls `HaSession.mediaArtwork` and decodes per reading keyed by artwork, size and session. `HomeAssistant.kt:437` fetches bytes with a shared HTTP client but no image-result cache/in-flight map. `CalmMediaRow` and `ArtworkScreensaverContent.ScreensaverPlayerBubble` reuse `MediaPlayerReading`, so they reuse UI logic but instantiate separate loaders.
- **Root cause / overlap:** image request lifetime is bound to individual composition rather than reusable content. Same-size repeated players can fetch/decode simultaneously; dashboard/dream transitions or new HA session identities repeat valid work. Slideshow already shares decoded frames and is not another instance of this defect.
- **Costs / consequence:** repeated network bytes, bitmap allocation and placeholder/image reappearance. Existing byte limits and decode buckets are valuable; benefit remains unmeasured. Different target sizes may justifiably require distinct decodes.
- **Solution / migration:** account/origin-isolated bounded media image cache with in-flight coalescing, decode-size/transform keys, cancellation per waiter and bounded decode parallelism. Replace only local fetch ownership; preserve renderer and security checks. Remove old per-instance network/decode path once all renderers consume the shared result.
- **Alternatives:** a proven image library is reasonable if its authentication, redirect, caching and lifecycle policy can reproduce the contract; replacing every image pipeline at once is unjustified. Sharing only HTTP cache would still duplicate decoding.
- **Complexity / dependencies / risks / verification:** medium; follow HAL-12 baseline and security tests. Test same URL multiple players, size changes, reconnect, account change/logout, URL content update and cancellation. Assert one simultaneous identical request/decode and byte-budget eviction, no cross-account reuse or credential forwarding. High confidence.

## HAL-05 — Unrelated preferences trigger whole-dream redecoding

- **Evidence:** `ArtworkScreensaver.kt:124` listener ignores the changed key and increments one revision. Lines 129–144 decode video/layout/apps and reread other sections for any revision. `DashboardArtwork.kt`, `finishFade`, writes `prefs.lastDashboardArtwork` on every successful new slide; its getter/setter is `LauncherPrefs.kt:40`.
- **Root cause / overlap:** preference observation is owned by the host and has one coarse invalidation domain. Last-image marker updates and unrelated utility preferences invalidate expensive configuration projections.
- **Consequence / execution:** while the dream runs a slideshow, slide persistence triggers layout/video/app parsing and object construction despite unchanged values. Compose structural equality may suppress some downstream work; it does not avoid executed getter parsing. Preference callbacks may coalesce within a write; do not assume one composition per changed key.
- **Solution / alternatives:** section/key-based observation and cached decode of unchanged raw strings; batch section writes. Start with filtering dream keys as an independent quick win, then share section snapshots with Activity. A single all-preferences Flow would preserve the same defect.
- **Benefits / complexity / dependencies:** lower allocation/CPU and clearer state ownership; medium shared solution or low-complexity local filter; coordinates HAL-11, no stored-format change.
- **Risks / verification:** missed updates/migrations; count layout parses across several slideshow advances, utility writes, selected-view changes and editor saves. Relevant changes must update both hosts; marker writes should trigger zero layout/app/video redecodes. High confidence, duration unmeasured.

## HAL-06 — Forecast work is not demand-driven

- **Evidence:** `HaConnectionRuntime.kt:63–72` discovers all weather IDs and requests both forecast types for every configured/discovered weather entity. `rememberHaConnection` has no hourly/daily demand parameter. `HomeAssistantWeather.kt:32` and `Theme.kt` define independent surface modes, default Off.
- **Root cause / related features:** presentation decides which forecast to show, but connection ownership never receives that demand. Weather current-state discovery remains necessary; forecast requests are a different capability.
- **Costs / reliability:** unnecessary subscriptions/fallback requests and parsing even with forecast display Off; errors for unsupported forecast types and longer initialization can delay Connected status. This does not establish that HA server performs expensive provider fetches per subscriber.
- **Solution / alternatives:** union forecast demand per relevant entity and active surface while retaining current-weather fallback. Add/remove demand separately where possible. Gating only by one global flag would break independent dashboard/popup/dream modes; reconnecting the entire socket on every display toggle creates churn.
- **Benefits / complexity / dependencies:** lower network/state overhead; medium complexity; section settings HAL-05 useful but not mandatory. Preserve protocol fallback semantics.
- **Risks / verification:** all four modes on each surface, cold dream, overlapping hosts, multiple weather entities, hourly unsupported/daily fallback, mode change while visible, offline reconnect. Assert no forecast subscriptions with no demand and no lost current-weather values. High confidence.

## HAL-07 — Editor transformations share the remote-input thread

- **Evidence:** `DashboardEditor.kt:187–190` dispatches the API to Main; state serialization at line 281, catalog/template encoding in API branches, `dashboardLayoutFromJson` validation on Apply, export/import callbacks and `WebConfiguration.sections/updateRevision` execute within that call. HTTP envelope parsing is already on the HTTP worker; `runBlocking` itself is not on main.
- **Root cause:** UI mutation and pure JSON/model transformation share one API closure and thread boundary. Large inputs (up to 4 MiB; up to 64 views) increase work. The API timeout does not preempt non-suspending validation/encoding.
- **Impact:** suspected remote-input delay/allocation bursts during large API requests; no measured ANR or stall. Ordinary small editor requests may be cheap.
- **Solution / alternatives:** snapshot immutable inputs on main, validate/encode on a bounded worker, then recheck revision before main-thread commit. Consider revision-keyed encoded fragments only when repeated reads justify them. Moving all API work to IO would race Compose state; disabling validation would weaken correctness.
- **Benefits / complexity / dependencies:** input isolation; medium-to-high complexity; typed immutable sections from HAL-05/11 reduce risk but prototype one endpoint first. Keep failure confirmation and browser drafts.
- **Verification / risks:** trace large Apply/catalog/export while holding D-pad; concurrent editors, revision conflicts, teardown mid-request, invalid layout, imports with credentials and restore consumption. No stale commit or false acknowledgement. High confidence in execution, medium in impact.

## HAL-08 — HA protocol backlog is unbounded

- **Evidence:** `HomeAssistant.kt:367` uses `Channel.UNLIMITED`; OkHttp callback parses and `trySend`s JSON; main session coroutine dispatches responses/events at line 397.
- **Root cause / impact:** producer and main-thread consumer have no capacity/overload policy. A large/frequent state feed combined with blocked main dispatch could accumulate JSON objects, stale actions/state and memory pressure. This is a suspected overload scenario, not observed ordinary-session growth.
- **Solution / alternatives:** measure queue depth and rates; introduce a finite protocol queue with explicit overflow termination/reconnect/snapshot reconciliation. Do not conflate arbitrary messages: command replies and compact entity deltas cannot safely be dropped. Moving dispatch off main requires an explicit model-thread boundary.
- **Benefits / complexity / dependencies:** bounded failure under overload; medium complexity; depends on HAL-12 instrumentation and HA reconnect tests. Not a reason to rewrite the protocol.
- **Risks / verification:** large first snapshot, slow consumer, bursts, pending command responses, cancellation, overflow and repeated reconnect backoff. Confirm no silent dropped deltas or retry storm. Confidence medium for practical impact.

## HAL-09 — Thumbnail count is not a memory budget

- **Evidence:** `Frigate.kt:151` caches `THUMBNAILS_KEPT` images by count; line 283 decodes the entire response stream with `BitmapFactory.decodeStream`. No response-byte, pixel or decode-target bound is applied in this path. Concurrent same-path misses can decode separately.
- **Root cause / affected features:** renderer uses a small thumbnail, but client accepts server-selected image dimensions and entry sizes. Actual trusted Frigate images may be modest; current memory impact is unknown.
- **Solution / alternatives:** bounded encoded bytes, inspect bounds, sample to display dimensions, byte-weighted eviction and in-flight deduplication. Keep authenticated/pinned Frigate transport separate. Reducing the entry count alone does not bound one large allocation.
- **Benefits / complexity / dependencies:** predictable memory; medium complexity; share safe decode primitives with HAL-04 if suitable, not credentials/cache namespaces.
- **Risks / tests:** sharpness, corrupt/oversized images, redirected auth, duplicate loads, cancellation and cache eviction. Verify limit enforcement before full decode and actual alert-grid quality on TV. High confidence.

## HAL-10 — Video readiness remains true after normal transitions

- **Evidence:** `VideoBackground.kt:78–84`, `onMediaItemTransition`, changes current item/epoch/generation but does not clear `hasFrame`. `onRenderedFirstFrame` at line 100 sets it true. The watchdog at lines 220–224 runs only while false; explicit bind/failure/release clear it.
- **Root cause / affected path:** one flag represents both a surface having displayed anything and the current item having produced a frame. After a valid first item, a later item that stalls without a Player error may retain true and evade the 20-second watchdog. With switchSeconds=0 it may remain stalled; with a positive interval the switch timer may advance it without first-frame validation.
- **Solution / alternatives:** track per-item first-frame readiness separately from whether the old surface image may remain visible. Reset watchdog state on item generation and require matching first-frame events; retain prior frame presentation to avoid a black flash. Blindly setting the one flag false can alter visuals between every valid item.
- **Benefits / complexity / dependencies:** bounded recovery for later bad playlist members; medium complexity; independent of catalog/config refactors.
- **Risks / verification:** first good → stalled second with interval 0 and positive, repeats/random start/seek, fast valid transitions, target handoff, stale callbacks and all-items-failed retry. Fake controller events can prove state; hardware playback must prove recovery/visual parity. High confidence state gap, medium confidence manifestation without device fault injection.

## HAL-11 — Startup settings ownership is spread across hosts

- **Evidence:** `MainActivity.kt:132–158` synchronously loads app JSON, cameras, dashboard layout and video settings before composition. `LauncherPrefs.kt:70` may decode installed apps to infer default services; `onResume` rereads video settings. Dream independently reconstructs the same sections at `ArtworkScreensaver.kt:129`. `MainActivity` contains configuration save/reload/import/migration plus focus/lifecycle logic.
- **Root cause / overlap:** configuration persistence getters double as decoders and hosts maintain manual mirrors. SharedPreferences caches raw values, so this is not proof of repeated filesystem reads; the confirmed duplication is parsing/representation/orchestration.
- **Impact:** suspected cold-start parsing and allocations; maintenance risk when a new field updates one host but misses another. No startup timing has been measured.
- **Solution / alternatives:** reuse existing storage/codecs with small typed section snapshots and one decode per relevant raw value; independently cold-startable hosts consume them. Trace before moving required settings out of startup. A blanket DataStore/Room/ViewModel rewrite would add migration risk without evidence.
- **Benefits / complexity / dependencies:** clearer authority, fewer parses, smaller Activity; medium-to-high complexity; HAL-05 and HAL-02 should precede broad changes.
- **Risks / verification:** first-run, process death, empty layouts versus missing layouts, Assistant normalization, selected view, defaults, secrets, imports/Restore and separate popup settings. Measure TTID, first focus and successful app launch; do not improve blank-screen timing at the expense of usability. High ownership confidence, medium latency confidence.

## HAL-12 — Performance claims lack a controlled baseline

- **Evidence:** one application module in `settings.gradle.kts`; no app-specific benchmark/profile module or `reportFullyDrawn`/Compose drawn marker found in source. JVM and browser tests verify behavior but not input/presentation timing. Installed package is debuggable and counters are historical.
- **Problem / consequence:** no controlled evidence ranks startup, rendering, memory or main-thread suspects. Large refactors could regress the main objective while passing existing tests.
- **Solution:** the baseline and acceptance plan in [05](05_PERFORMANCE_AUDIT.md); add meaningful ready/focus/launch trace points after implementation approval. Compare release-equivalent builds with identical compilation/settings/device state. Profiles are a measured later optimization, not a substitute for removing work.
- **Alternatives / benefit / complexity:** ADB timing is a useful starting diagnostic but insufficient alone; Macrobenchmark plus Perfetto is medium setup effort. Independent setup is required before critical consolidation speed claims.
- **Risk / verification:** benchmark fixtures must not depend on private dashboard exports or optional network success. Confirm ready marker represents usable app-launch controls, collect distributions and preserve traces privately. Confidence high; Recommendation about instrumentation, no claim of measured slowness.

## HAL-13 — Logging has inconsistent secret handling

- **Evidence:** `AppLog.kt:75–82` sends raw message and Throwable to Android `Log.w/e`; line 139 only scrubs the string sent to retained files. `HomeAssistant.kt:51` and `Frigate.kt:67` log integration errors and their causes; `CameraView.kt:724` can log playback exceptions.
- **Root cause / security consequence:** redaction is a storage-sink helper rather than a boundary applied before all sinks. A credential-bearing URI or exception message could reach privileged/debug logcat even when the downloadable file is scrubbed. This audit did not collect or expose credentials and did not establish an observed leak. File scrub patterns also cover a limited set of query keys; arbitrary signed parameters are not generally recognized.
- **Solution / alternatives:** safe structured messages and a shared sanitized Throwable/string representation before either sink; avoid logging raw URLs/authorization at call sites. Preserve useful stack frames without secret payloads. Turning off all errors would hinder diagnosis.
- **Benefit / complexity / dependencies:** lower accidental credential exposure; low-to-medium complexity; independent quick fix, no new logging library required.
- **Risks / verification:** synthetic URL credentials, tokens, encoded parameters, nested causes and multiline errors through logcat and download; verify useful diagnostics and zero sensitive values in either. High confidence boundary mismatch, no measured incident.

## HAL-14 — Production/distribution checks and small cleanup

Current continuation: the [2026-11-26 validation follow-up](10_VALIDATION_FOLLOWUP.md) records passing debug APK signature/alignment checks, an 82-artifact runtime inventory/advisory scan and a targeted Guava source correction. The original evidence below describes the audit baseline; full release, native provenance, license and backup checks remain open.

- **Evidence:** `app/build.gradle.kts:42` enables R8 with optimized default rules, without explicit resource shrinking/new optimization block; no app-authored profiles found. Source application manifest has no backup policy or backup exclusion XML; device flags include ALLOW_BACKUP. Credentials, images and logs occupy otherwise backup-eligible locations. `tmdb_logo.xml` has no reference found; `RECEIVE_BOOT_COMPLETED` has no app-owned receiver found. These facts do not prove cloud backup actually runs on the SHIELD.
- **Recommendation / root cause:** release characteristics are less explicitly verified than debug functionality. Verify release merged manifest/resource shrinker output and current AGP defaults, choose a backup policy that accounts for credentials/large reproducible files and intentional manual settings transfer, and inspect packaging before removing unused assets/permission. Do not label dependencies vulnerable because of age; no vulnerability scanner was run.
- **Alternatives / benefits / complexity:** small cleanup has little runtime benefit and follows architectural work. Baseline/Startup Profiles may improve code loading but need compatible on-device generation/install/compilation validation; ProfileInstaller is already transitive. Conscrypt remains needed for Android 9. No blanket upgrades recommended.
- **Dependencies / risks / verification:** HAL-12 first for performance comparison; release build/signing validation needs a separately authorized APK build. Validate API 28 and API 30, signed update compatibility, minified dynamic assets/JSON/Media3 paths and backup/restore behavior; test every excluded state intentionally. Low-to-medium effort for policy, medium for profiles; backup choice is a product/distribution decision. Medium confidence about improvement value.

## Areas inspected without another significant finding

The capability map covers launcher registration, optional Home helper crash guard, local editor authorization/revisions, UTF-8, import selection, layout/visibility parsing, HA origin-restricted artwork authorization, Frigate pinning and Android-9 TLS fallback, camera lifecycle/decoder tracking, retained slideshow/video ownership, themes/color-cycle boundaries, media monotonic clock and bubble motion. Their existence is not itself a defect. Additional runtime verification is listed in [REVIEW_STATUS](REVIEW_STATUS.md).

The handoff itself contains superseded wording: early paragraphs of `docs/DECISIONS.md` still describe TV-owned integration settings and suppression of Android dreaming, while later sections explicitly replace both. Current source/local instructions and the later accepted direction were used for this audit. Reconcile those paragraphs during the next approved handoff update; this review did not overwrite the existing behavior contract or treat stale text as a reason to restore removed features.

The declared component binding permissions are appropriate to the services inspected. MainActivity's exported editor action enables the existing local listener; it does not create a separate authorization bypass within that listener. Cleartext local integrations and unpaired editor access are accepted product choices with documented trade-offs. No WebView, hardcoded production secret or root dependency was found in the inspected paths. This is not a penetration-test certification.

## Build/dependency review

The offline resolved releaseRuntimeClasspath report succeeded and shows Gradle-selected shared versions, including profileinstaller 1.4.1 and startup-runtime 1.2.0. Repeated dependency-tree nodes marked `(*)` are shared graph references, not duplicated packaged libraries. Media3 core/RTSP/UI/OkHttp modules have distinct roles. JUnit and desktop org.json are test-only. Release signing deliberately depends on external keys and macOS Keychain when those files exist; this remains a portability limitation for release automation. Build timestamps invalidate generated BuildConfig per invocation; their build-speed significance is minor next to runtime work.

Unit verification compiles debug source, not a minified release APK. No release APK size, native ABI packaging, R8 mapping, release resource reachability, CVE/SBOM scan or release initializer timing was established. Do not upgrade the toolchain or remove native Conscrypt as a speculative performance fix.
