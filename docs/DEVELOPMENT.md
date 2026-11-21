# HAL development handoff

At the start of every new HAL chat, read the checkout's `AGENTS.md`, this handoff, and the relevant sections of [DECISIONS.md](DECISIONS.md) and [README.md](../README.md) before starting the task. The local `CLAUDE.md` imports `@AGENTS.md`.

## Keeping work understandable across chats

“Update the docs” means reconcile the current conversation's accepted decisions and code with all affected project documentation, including the local agent instructions. Record **what changed, why, compatibility implications, what supersedes older behavior, what was verified, and what remains open**. Keep user directions distinct from assistant proposals. Update existing statements rather than appending contradictory history.

The handoff is the starting point for a new chat; it is not a claim that every previous conversation is automatically loaded. Consult the local chat index and Git history when details are missing. Do not ask the user to repeat a decision already recoverable there. Ask when a real ambiguity remains after checking. Do not infer implementation authorization from a research-only chat.

`AGENTS.md` and `CLAUDE.md` are intentionally ignored by Git. They stay in this checkout but are absent from fresh clones/worktrees unless supplied there. The README and `docs/` are portable documentation. Verify local instruction availability before working in another checkout. Do not change this sharing policy silently.

## Review baseline and superseded behavior

The documentation review on 2026-11-16 used code at `0fa2ee2`, recent merge history, tests, and the relevant HAL conversation messages. It covered the dashboard/editor, visibility, media, artwork, launcher, network status, persistence, and integration boundaries. It was a source/documentation review, not a physical-TV certification or a fresh comprehensive security audit.

| Earlier behavior or proposal | Current direction | Useful history |
| --- | --- | --- |
| HomeBoard name/package | HAL, `com.evofactory.hal`; old package installations are separate | `7a852bf` |
| TV-side dashboard configuration, live HA layout dependence | Bundled browser editor and independent HAL templates; legacy configuration seeds migration | `072da15` |
| Editor popup owns listener, 15-minute timeout | Explicit Enable Web Editor toggle; popup can close; no idle timeout | `bef52b9` |
| Dock, Watch Next row and chevrons | Home-only Apps card; Watch Next is an artwork source | `bef52b9` |
| 10 grid rows, cropped Apps artwork, reduced header gap | 15 viewport rows, original artwork aspect ratio, normal header gap | `bef52b9` |
| Last app on every Home press | First visible tile for handled Home; last available tile for ordinary navigation | `064c2ff` |
| TMDB API key and automatic local-artwork takeover | JustWatch without a key; explicit local/media source selection | `a074786` |
| Unsupported conditions ignored | Supported rules preserved; unsupported conditional cards skipped with notice | `57b6398` |
| Only whole-card visibility | Per-item rules and Release space / Keep space empty | `064c2ff` |
| Fixed-width web grids | Up to 24 columns, six on screen, horizontal scrolling | `2a690a9` |
| Dragging/column placement failures | Rendered-track gesture calculations and nearest-open-row column moves | `cc46e1a` |
| Paired Restore, TV and USB file dialogs, direct TV transfer | Separate Apply and Restore points, browser-only settings files | [Browser settings and Restore handoff](HANDOFF.md) |
| Classic and Glass selector, then Glass only | Glass, Glass Dark and Calm in Appearance with a shared saved opacity override | [Appearance decisions](DECISIONS.md#artwork-appearance-and-idle-behavior) |

Recheck Git and the device for each install task. See [Verification for Glass Dark and scalable artwork](#verification-for-glass-dark-and-scalable-artwork) for the latest confirmed SHIELD installation.

## Source map

Kotlin sources live in `app/src/main/java/com/evofactory/hal/`, JVM tests in `app/src/test/java/com/evofactory/hal/`.

| Area | Start here | Relevant tests/checks |
| --- | --- | --- |
| Activity, settings, migration, persistence | `MainActivity.kt`, `LauncherPrefs.kt`, `DashboardLayout.kt` | `HaCardLayoutTest.kt`, `DashboardEditingTest.kt`; separate restore points and paired snapshot migration |
| Assistant retirement | `DashboardLayout.kt` (`retireAssistant`), `MainActivity.kt` (one-time migration and legacy file normalization), `LauncherPrefs.kt` (retirement marker), `DashboardEditor.kt` and editor HTML (Home-only editing) | `CalmDashboardTest.kt`: collision-safe migration, preserved rules/names/focus, 64-view reload; device migration and old-file import |
| Layout and D-pad focus | `MainActivity.kt`, `HomeAssistantTab.kt`, `DashboardAppsCard.kt`, `AppRow.kt` | `HaCardLayoutTest.kt`, `DockTileKeysTest.kt`, `DashboardEditingTest.kt`; device navigation |
| HA connection and template import | `HomeAssistant.kt`, `HomeAssistantPanel.kt`, `HaDashboard.kt` | `CardVisibilityTest.kt`, `HaCardLayoutTest.kt`; reconnect snapshot |
| State/action/media behavior | `HaStateCue.kt`, `HaPrimaryAction.kt`, `HaMediaPlayer.kt` (artwork sizing, decoding and progress), `HomeAssistantTab.kt` (panel allocation) | Corresponding `*Test.kt`; live player/device checks |
| Visibility model | `CardVisibility.kt`, `DashboardLayout.kt` | `CardVisibilityTest.kt`; hidden-focus recovery |
| Web editor | `DashboardEditor.kt`, `app/src/main/assets/dashboard-editor/{index.html,editor.js,editor.css}` | JS syntax, real browser pointer events, API confirmation |
| Artwork | `ArtworkCatalog.kt`, `JustWatchReleases.kt`, `ArtworkRefreshJob.kt`, `DashboardArtwork.kt`, `LocalArtwork.kt` | `JustWatchReleasesTest.kt`, `DashboardArtworkTest.kt`, `WatchNextTest.kt`; Android job |
| Glass, Glass Dark, Calm and opacity | `Theme.kt` (palettes and card fills), `DashboardArtwork.kt` (slideshow shade), `LauncherSettingsDialog.kt` (Appearance), `LauncherPrefs.kt` (selection and override), `Screensaver.kt` (camera screensaver) | Contrast and preference tests, on-device focus and legibility over black and bright artwork |
| Calm presentation | `CalmDashboard.kt` (summary rail, fixed-hue lock badge and focused dialog), `CalmOverview.kt` (measured stacked/horizontal/scaled overview), `CalmMediaRow.kt` (equal-width player slots, capability-gated transport and focus recovery), `HomeAssistantTab.kt` (existing card renderer, Home targeting and visibility recovery), `HomeTopBar.kt` (right-hand clock and pill navigation), `AppRow.kt` (compact shortcuts), `MainActivity.kt` (forward remote shortcuts after panel dismissal) | `CalmDashboardTest.kt`, theme preference coverage in `DashboardEditingTest.kt`; device focus and panel checks |
| Utility buttons and Calm row/weather cues | `SettingsButton.kt` (32 dp circles, time-line offset, size-aware labels), `NetworkStatus.kt` (matching network button), `DashboardAppsCard.kt` (non-focusable reachable-row chevrons), `CalmOverview.kt` and `CalmDashboard.kt` (condition icon in measured overview) | `AppsRowIndicatorsTest.kt`; TV alignment, focus-label clearance and overview fit |
| Apps collapse and color cycling | `DashboardAppsCard.kt` (draw-only movement), `AppsCardIdleState.kt` (30-second idle and wake keys), `HomeOverrideService.kt` (intercepted Home), `UiColorCycle.kt` (timing, filter and drawing clearance), `MainActivity.kt` (layer boundaries), `DashboardArtwork.kt` (separate slideshow layer), `LauncherSettingsDialog.kt` and `LauncherPrefs.kt` | `AppsCardIdleStateTest.kt`, `UiColorCycleTest.kt`; TV focus, persistence, and contrast at both ends and between them |
| Cameras and decoder limits | `Cameras.kt`, `CameraView.kt`, `StreamDescription.kt`, `StuckPlayers.kt` | `LiveSlotsTest.kt`, `StreamDescriptionTest.kt`; hardware streams |
| Frigate | `Frigate.kt`, `FrigateAlerts.kt`, `FrigateDialog.kt` | Integration checks for login, clips, and certificate handling |
| Network, logs, idle, Home helper | `NetworkStatus.kt`, `AppLog.kt`, `HalApp.kt`, `Screensaver.kt`, `HomeOverrideService.kt` | Device callbacks, crash download, screensaver handoff |
| Browser settings files | `ExportImport.kt` (versioned JSON and selection), `DashboardEditor.kt` (listener API), `MainActivity.kt` (import semantics), browser `editor.js` | `DashboardEditingTest.kt`; import preview, credential defaults, Unicode downloads, and confirmation |

## Build and verification workflow

Build an APK **only when explicitly requested**. For ordinary implementation work, run relevant tests/source checks. Documentation-only edits need content/link/source checks, not an APK. Do not claim a test was run when only its source was reviewed.

On the current Windows checkout, use the existing local toolchain and cached test dependencies. The Gradle daemon criteria require Java 25; an arbitrary bundled Android Studio JDK may not satisfy that. From the repository root:

```powershell
$halRoot = (Get-Location).Path
$env:JAVA_HOME = Join-Path $halRoot 'build/hal-toolchain/jdk-25.0.5.0.2+1'
$env:ANDROID_HOME = Join-Path $halRoot 'build/hal-toolchain/android-sdk'
$env:ANDROID_USER_HOME = Join-Path $halRoot 'build/hal-toolchain/android-user'
$env:GRADLE_USER_HOME = Join-Path $halRoot 'build/tooling/gradle-home'
& (Join-Path $halRoot 'build/hal-toolchain/gradle-9.8.2/bin/gradle.bat') --offline :app:testDebugUnitTest
```

Check those directories first; they are local tooling, not repository contents. If dependencies are missing, report the specific failure rather than silently treating it as a test pass. Use `:app:assembleDebug` only for an authorized APK build. The APK is `app/build/outputs/apk/debug/app-debug.apk`.

Keep `ANDROID_USER_HOME` pointed at the existing directory for SHIELD updates: it contains the established debug signing key. An earlier build accidentally used another debug key and Android rejected the update. Do not uninstall HAL to work around a signing mismatch; recover the correct signing setup. The release signing configuration separately expects a key under the user's `.hal` directory and macOS Keychain access when that key exists.

For an authorized install, inspect `adb devices -l` and the selected target's model first. The reference target is an NVIDIA SHIELD TV driving an LG OLED. A historical address is not a permanent identity. Use `adb -s <verified-target> install -r <apk>` and verify the package version/update time for `com.evofactory.hal`. Installation success is not a visual or remote-navigation test.

Before committing directly to `main` or merging into `main`, review the full incoming diff against the project handoff. Update affected documentation first and include the tracked updates in the same direct commit or pull request. If the documentation is already accurate, proceed without a documentation edit.

When asked to merge and clean up, verify the feature commits are present on `origin/main` before deleting refs. Fast-forward local main and verify both local and remote feature refs are gone. Preserve unmerged work.

### Verification for browser settings and Restore

Before PR #10 merged, all 51 JVM tests passed and the bundled editor passed headless Chrome checks at 1280 px and 420 px. The browser used a synthetic API and checked settings selection, confirmation and failure paths, and UTF-8 download read-back. In this checkout, reproduce the browser check with `node build/editor-transfer-check.cjs`. The runner is under the Git-ignored `build/` directory. See [the branch handoff](HANDOFF.md#verification) for coverage and the remaining device checks.

### Verification for softer Glass

On 2026-11-16, the `codex/softer-glass` working copy based on `524175b` passed all 51 JVM tests and assembled a debug APK with the existing debug key. ADB confirmed an in-place update of HAL 1.2.0 (version code 17) on the SHIELD. The install confirmed package replacement. The LG visual check and a timed check of foreground editor wake behavior are open.

### Verification for Apps collapse and color cycling

On 2026-11-17, `codex/dashboard-oled-options` passed all 53 JVM tests with `:app:testDebugUnitTest`, including the reverse cycle, RGB endpoints, alpha preservation, and intermediate channel contrast. The debug APK built with the existing key and installed in place on the SHIELD. ADB confirmed HAL 1.2.0 (version code 17) with an updated installation timestamp. The Apps animation, remote navigation, and color rendering still need verification on the TV.

The subsequent dark color cycle and media-panel alignment changes passed all 55 JVM tests. The added checks cover true black, secondary text contrast across the cycle, and sizing for one panel, multiple panels, and overflow. The revised debug APK installed in place on the SHIELD, and ADB confirmed the package update at 22:28 local time on 2026-11-17. Visual verification of the updated appearance and panel alignment is open.

### Verification for Glass Dark and scalable artwork

All 59 JVM tests passed on 2026-11-17. The added tests cover appearance preference fallback and persistence, Glass Dark text contrast over the brightest shaded artwork, and artwork sizing and bitmap sampling. The signed debug APK installed in place on the SHIELD at 22:52 local time. Android reported a successful cold launch of HAL 1.2.0 (version code 17). On-screen legibility, live player scaling, and navigation frame pacing remain open.

The performance review moved playback progress reads out of panel composition, cached scroll fade brushes, removed offscreen compositing where no fade is visible, and sized artwork decodes to the display. These changes follow Android's guidance for [Compose phases](https://developer.android.com/develop/ui/compose/performance/phases) and [bitmap sampling](https://developer.android.com/topic/performance/graphics/load-bitmap).

### Verification for Calm

On 2026-11-18, `codex/calm-launcher` passed all 66 JVM tests and assembled the debug APK with the established signing key. New coverage checks that summaries exclude hidden/reserved rows and do not describe missing light states as off; preference coverage includes Calm. `git diff --check` passed. The final APK installed in place on the verified NVIDIA SHIELD Android TV as HAL 1.2.0 (version code 17), with Android reporting update time **23:22:15 local time** and a successful launch. Calm was selected through Appearance and persisted across subsequent APK updates/launches; existing 40% opacity, color cycling, slideshow selection and Down collapse were retained.

ADB-driven screen checks verified the clock/pill header, live weather and light summaries, original app banners, expansion/collapse of the existing Apps card, explicit Down entry to summaries, Left/Right summary selection, OK opening the light card with control focus and half-width rows, the then-current two-stage Back return to the same summary (superseded by one-press dismissal below), Apps-to-summary return, and the Settings shortcut leaving an opened panel. The shortcut check confirmed Android accessibility focus on the cog after dismissal. The final crash-log check returned no AndroidRuntime errors. Panel opening hides the overview to avoid overlapping text through transparent fills. These are screenshot/input checks, not seating-distance validation or measured frame pacing.

The 2026-11-19 follow-up removes the overview tagline, returns the clock to the right, adds the equal-width persistent player row and visible-lock badges. All 69 JVM tests and Kotlin compilation passed for this follow-up, including lock aggregation/visibility, media-slot spacing and transport capability checks; `git diff --check` passed. At the user's subsequent request, `:app:assembleDebug` built the APK with the established debug signing key. ADB reverified the connected NVIDIA SHIELD Android TV and installed the update in place with `install -r`. Android confirmed HAL 1.2.0 (version code 17), update time **2026-11-19 22:25:26 local time**, and a successful cold launch. This confirms build/install/launch; the revised media row and lock badges still need visual and live-control checks. Recheck media-row artwork, all four transport controls, disabled controls, multi-player equal allocation, narrow widths, reserved player slots, Home targeting, disappearing players, header/summary/Apps navigation and mixed cards on the SHIELD. Check green/red/unknown lock badges, multiple locks and conditional hiding.

The subsequent correction fixes Calm media height at Glass’s natural 84 dp (shared 76 dp artwork / 4 dp panel-padding constants), places transport alongside metadata with a two-row arrangement in narrow tiles, and consumes Back at the popup before the shared card handler can focus its border. Check the corrected compact row below the center overview, wrapped transport focus, and single-press Back return on the SHIELD. The correction passed all 69 JVM tests, Kotlin compilation and `git diff --check`. At the user’s subsequent request, the corrected debug APK was assembled with the established signing key and installed in place using `adb install -r` on the reverified NVIDIA SHIELD Android TV. Android confirmed HAL 1.2.0 (version code 17), package update time **2026-11-19 22:48:12 local time**, and a successful cold launch. Visual sizing and one-press Back still need on-device verification; install/launch alone do not establish them.

Still check multiple views, long summary rails, hidden-card focus recovery, Home targets across views, app context menus, selects, gauges, history, expanded media panels and live camera/Frigate load under Calm. Those paths reuse existing implementations but were not all exercised on the device in this change. Check the screensaver handoff and extended idle/wake sequence, color cycling over a complete pass, black/no-artwork fallback and bright-slide legibility from the normal seating distance. The browser remains a grid editor rather than a Calm preview. No performance guarantee is inferred from a successful build/install.

### Verification for Home-only navigation and Calm reflow

The 2026-11-20 change retires Assistant, migrates its saved cards to Home views, removes the explicit intermediate header focus during handled Home presses, initially made a lone Home label non-interactive (now hidden by the later UI-polish change), measures the Calm overview before rearranging/scaling, preserves subdued semantic lock colors through color cycling, and makes Apps display one 96 dp row at a time. All 74 JVM tests and Kotlin compilation passed. New checks cover layout-mode choice, badge color constraints, available tabs, preserved migration metadata/key collisions and both former maximum view sets surviving serialization; `git diff --check` passed. The editor JavaScript passes `node --check`. At the user’s subsequent request, `:app:assembleDebug` built the APK using the established debug key. ADB was reconnected and the NVIDIA SHIELD Android TV model/manufacturer reverified before `install -r`. Android confirmed HAL 1.2.0 (version code 17), package update time **2026-11-20 22:58:35 local time**, and a successful cold launch.

Device checks remain: migration after startup with cached, missing and delayed HA templates; unchanged Home positions/Apps selection and imported visibility; Assistant-only and dual-layout version 1 imports; Home Restore after migration; header navigation with Home alone versus configured Cameras/Alerts; Home presses from a running app and a cold process without a Home-label flash; Apps row transitions in both directions, held keys, hidden/reserved tiles, context menus, collapse/wake keys and first-versus-last entry; complete focus outlines and original-aspect artwork; full-size stacked overview, horizontal fallback and last-resort scaling with/without media and with the fixed Home camera. Inspect lock badges at both ends of a color cycle. Build/install/launch confirmation does not close these functional and visual checks.

### Verification for Calm UI polish

On `codex/calm-ui-polish`, the smaller utility buttons, Apps row indicators, weather icon and subsequent hidden lone Home label compile and all **76 JVM tests pass**, including reachable Apps rows above/below the selected row, single/middle rows and hidden reserved rows. `git diff --check` passes. At the user’s subsequent request, `:app:assembleDebug` built the APK with the established debug key. ADB verified manufacturer NVIDIA and model SHIELD Android TV at `10.1.0.130:5555`, then `install -r` succeeded. Android confirmed HAL 1.2.0 (version code 17), package update time **2026-11-20 23:51:25 local time**, a successful cold launch and a running process. The new process had no AndroidRuntime error entries. Device checks remain: Settings/network alignment and complete focus circles/labels in Glass, Glass Dark and Calm; first/middle/last Apps row chevrons, visibility changes and collapse/wake behavior; weather icon and complete overview text with/without media and the fixed Home camera. These are source/unit checks, not physical-TV visual or frame-pacing verification.

### Checks that need more than unit tests

On 2026-11-18, `codex/apps-idle-and-appearance` passed all 64 JVM tests with `:app:testDebugUnitTest`, including five new idle/wake-key tests. Kotlin compilation and `git diff --check` passed. The README introduction was shortened, focused Apps gained the 30-second collapse timeout, both themes now use Glass's slideshow shade, and the top-bar filter layer gained bottom drawing clearance. The former Glass Dark contrast test now covers opaque dark surfaces; the lighter artwork shade removes its earlier guarantee over bright slides with transparent cards. At the user's request, `:app:assembleDebug` then built the APK using the existing debug key. ADB verified the NVIDIA SHIELD TV and installed HAL in place with `install -r`; Android confirmed HAL 1.2.0 (version code 17), package update time 22:18:12 local time, and a successful warm launch. TV visual and remote checks remain open.

- **Editor gestures:** use real pointer events in an extension-free browser, at desktop and narrow widths. Cover diagonal drag, resize, edge auto-scroll, pointer leaving the preview, and committing a focused field before dragging. Check Column moves, save/reload, and unchanged neighboring cards.
- **TV focus:** test all Apps boundaries, Home versus ordinary entry, delayed app-list loading, view changes, off-screen cards, and half-width focus outlines. Test a visible Home target, a missing/hidden target and one that disappears during view entry. Verify no intermediate header highlight, cold-start/delayed Apps loading, and a consumed request on later view visits. With only Home available, verify its name is hidden and header fallbacks reach the network button. Enable Cameras and/or Frigate and verify Home plus all other available tabs are visible and selectable. Check ordinary cards and media-only panels at both focus levels.
- **Visibility:** round-trip HAL and imported rules, nested NOT, missing/unknown states, comparison-only subscriptions, reconnect deletion, item reorder/remove, both spacing modes, and an older layout without new fields.
- **Artwork:** verify source isolation, limits, shuffle/fallback, caption metadata, and Android job execution. Desktop regex tests missed the previous Android 11 literal-brace incompatibility.
- **Appearance:** check Glass, Glass Dark and Calm from the normal seating distance against black and over a bright artwork slide. Check secondary text, state cues, and D-pad focus, including both ends of the outline on half-width items. Confirm Appearance selection survives restart, a saved opacity override carries between themes, and **Use theme default** restores 50%. Check camera screensaver text if that screensaver is in use.
- **Apps collapse and color cycling:** verify Off, Up, and Down across a restart. Leave Apps focused for 30 seconds, then check that OK, directions, Back, shortcuts, volume/media keys and HAL-handled Home only reopen it, including repeats/release; Power should work normally. Check held keys, menu dismissal, foreground return and the empty-card target. Verify complete Settings/network circles and labels with cycling on and off, including the dropdown. Enter collapsed Apps from the header and adjacent cards, and confirm normal entry, Home entry, long-press menus, and visibility fallback. Check both edges on a scrolled view. Compare the start, midpoint, and far end of the cycle over a static slide. Check text contrast, true black without a slide, original app icons and banners on Home and in All apps, original player artwork, the return leg, and immediate Off. Check live camera and Frigate video while their labels cycle. Compare Glass and Glass Dark slideshow brightness with local and media artwork, and recheck dim text with low card opacity.
- **Media panel alignment and artwork:** put one player, multiple players, and an ordinary card in matching grid rows. Resize a panel vertically and horizontally and check that the artwork grows while labels and actions fit. Check the visible outlines at both focus levels, with and without headings, with conditional slots reserved or released, and with small slots that must scroll. On a shared card, compare media row content with neighboring sensor and control content, including two half-width items in one row.
- **Idle:** with editor disabled, confirm the SHIELD's screensaver takes over; with the listener enabled and popup closed, confirm foreground wake behavior. Test editor disable restores normal behavior. Camera screensaver behavior is a separate check.
- **Save failures:** distinguish a rejected request from a lost success response. Check that the selected draft remains on unconfirmed Apply or Restore. Apply Home, edit its draft, then Restore Home and verify checkpoint consumption and draft replacement. Check that stale Assistant Apply/Restore requests are rejected. Check UTF-8 labels by save and read-back.
- **Settings files:** test selection, unchecked credential choices, malformed JSON and layouts, unsupported versions, request size including the wrapper, and download contents. Verify cancel and rejected import preserve drafts. Check that a confirmed import reloads the Home draft and that catalog trouble is reported separately. Use synthetic settings and check both desktop and narrow viewports. On the TV, verify the browser file round trip against the Android listener and the Home Assistant reconnect after importing a login.

## Outstanding questions and limitations

These are recorded for continuity, not authorization to implement changes.

- **OLED:** the exact model of the 77-inch 2023 LG and its panel behavior remain unconfirmed. Idle dimming and camera-screensaver changes remain research proposals.
- **TiviMate:** earlier research found no documented channel shortcut API; installed-app intents were not tested. HAL has ordinary app launching, not a shipped channel-favorites integration. Reverify external API claims before acting on that research.
- **Editor save durability:** success confirms application handling; SharedPreferences uses `apply()`. There is no disk-write acknowledgement in the current API. The revision is listener-scoped, not a general transaction/version system for every possible preference writer.
- **Preview fidelity:** Calm renders saved cards as summaries and focused panels; the editor continues to show the saved grid. The browser preview preserves grid placement and ordinary half-width rows but lists only the first eight Apps labels and does not reproduce live media panels, state cues, or conditional hiding. Do not present it as a pixel-identical TV preview.
- **Backup scope:** browser settings exports exclude local slideshow images, appearance, and artwork-source configuration. File exports are unencrypted and can contain reusable HA tokens, Frigate credentials, and camera passwords when selected.
- **Transport/security follow-up:** the original security review left open issues. Current code still allows cleartext integration traffic, defaults a bare HA address to HTTP, and uses first-certificate trust/pinning for Frigate with hostname verification disabled. Secrets are stored in app preferences, and the manifest has no explicit backup exclusion policy. The browser editor deliberately uses local HTTP without authentication.
- **Verification limits:** prior chats repeatedly distinguished tests/build/install from physical-TV validation. Device focus, screensaver timing, and live playback should be verified when affected; successful earlier APK installations do not close those checks automatically.

The full behavior explanations and implementation limits are in [DECISIONS.md](DECISIONS.md). Update this list when a question is resolved, with the actual evidence, instead of carrying it indefinitely as an open issue.
