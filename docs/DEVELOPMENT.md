# HAL development handoff

Start with the checkout's `AGENTS.md`, then this file and the relevant sections of [DECISIONS.md](DECISIONS.md). [README.md](../README.md) is the user guide. The local `CLAUDE.md` imports `@AGENTS.md`; it should not maintain a competing copy of project rules.

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
| Classic and Glass selector | Glass only, listed in Appearance with saved opacity controls | [Appearance decisions](DECISIONS.md#artwork-appearance-and-idle-behavior) |

Do not treat old build/install messages as proof of current deployment. The latest reviewed installation report was `cc46e1a` on the SHIELD; its feature commits were subsequently merged as `0fa2ee2`. Recheck Git and the device for any new install task.

## Source map

Kotlin sources live in `app/src/main/java/com/evofactory/hal/`, JVM tests in `app/src/test/java/com/evofactory/hal/`.

| Area | Start here | Relevant tests/checks |
| --- | --- | --- |
| Activity, settings, migration, persistence | `MainActivity.kt`, `LauncherPrefs.kt`, `DashboardLayout.kt` | `HaCardLayoutTest.kt`, `DashboardEditingTest.kt`; separate restore points and paired snapshot migration |
| Layout and D-pad focus | `MainActivity.kt`, `HomeAssistantTab.kt`, `DashboardAppsCard.kt`, `AppRow.kt` | `HaCardLayoutTest.kt`, `DockTileKeysTest.kt`, `DashboardEditingTest.kt`; device navigation |
| HA connection and template import | `HomeAssistant.kt`, `HomeAssistantPanel.kt`, `HaDashboard.kt` | `CardVisibilityTest.kt`, `HaCardLayoutTest.kt`; reconnect snapshot |
| State/action/media behavior | `HaStateCue.kt`, `HaPrimaryAction.kt`, `HaMediaPlayer.kt` | Corresponding `*Test.kt`; live player/device checks |
| Visibility model | `CardVisibility.kt`, `DashboardLayout.kt` | `CardVisibilityTest.kt`; hidden-focus recovery |
| Web editor | `DashboardEditor.kt`, `app/src/main/assets/dashboard-editor/{index.html,editor.js,editor.css}` | JS syntax, real browser pointer events, API confirmation |
| Artwork | `ArtworkCatalog.kt`, `JustWatchReleases.kt`, `ArtworkRefreshJob.kt`, `DashboardArtwork.kt`, `LocalArtwork.kt` | `JustWatchReleasesTest.kt`, `DashboardArtworkTest.kt`, `WatchNextTest.kt`; Android job |
| Glass and opacity | `Theme.kt` (palette and card rendering), `LauncherSettingsDialog.kt` (Appearance), `LauncherPrefs.kt` (override), `Screensaver.kt` (camera screensaver) | On-device Appearance, upgrade, and screensaver checks |
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

When asked to merge and clean up, verify the feature commits are present on `origin/main` before deleting refs. Fast-forward local main and verify both local and remote feature refs are gone. Preserve unmerged work.

### Verification for browser settings and Glass

The last implementation run passed all 51 JVM tests and ran the bundled editor in headless Chrome at 1280 px and 420 px. The browser used a synthetic API and checked settings selection, confirmation and failure paths, and UTF-8 download read-back. In this checkout, reproduce the browser check with `node build/editor-transfer-check.cjs`. The runner is under the Git-ignored `build/` directory. See [the branch handoff](HANDOFF.md#verification) for coverage and the remaining device checks.

### Checks that need more than unit tests

- **Editor gestures:** use real pointer events in an extension-free browser, at desktop and narrow widths. Cover diagonal drag, resize, edge auto-scroll, pointer leaving the preview, and committing a focused field before dragging. Check Column moves, save/reload, and unchanged neighboring cards.
- **TV focus:** test all Apps boundaries, Home versus ordinary entry, delayed app-list loading, view changes, off-screen cards, and half-width focus outlines. For the Assistant Home-focus fix, test a visible target, a missing or hidden target, and a target that disappears during view entry. Revisit Assistant to check that Home focus is consumed. Check ordinary cards and media-only panels at both focus levels.
- **Visibility:** round-trip HAL and imported rules, nested NOT, missing/unknown states, comparison-only subscriptions, reconnect deletion, item reorder/remove, both spacing modes, and an older layout without new fields.
- **Artwork:** verify source isolation, limits, shuffle/fallback, caption metadata, and Android job execution. Desktop regex tests missed the previous Android 11 literal-brace incompatibility.
- **Appearance:** after an authorized upgrade on the TV, confirm that Appearance displays Glass and the remote reaches the opacity controls. Check that an old explicit override is retained, **Use theme default** restores 50%, and the camera screensaver text stays readable.
- **Idle:** with editor disabled, confirm the SHIELD's screensaver takes over; with the listener enabled and popup closed, confirm foreground wake behavior. Test editor disable restores normal behavior. Camera screensaver behavior is a separate check.
- **Save failures:** distinguish a rejected request from a lost success response. Check that the selected draft remains on unconfirmed Apply or Restore. Apply Home, Apply Assistant, then Restore Home and verify that Assistant keeps its saved layout, draft, Undo history, and restore point. Check UTF-8 labels by save and read-back.
- **Settings files:** test selection, unchecked credential choices, malformed JSON and layouts, unsupported versions, request size including the wrapper, and download contents. Verify cancel and rejected import preserve drafts. Check that a confirmed import reloads both drafts and that catalog trouble is reported separately. Use synthetic settings and check both desktop and narrow viewports. On the TV, verify the browser file round trip against the Android listener and the Home Assistant reconnect after importing a login.

## Outstanding questions and limitations

These are recorded for continuity, not authorization to implement changes.

- **OLED research:** softer Glass text/borders, a bounded editor wake period, and a less static camera screensaver were proposed but not implemented. The user explicitly requested research only. Any change must reconcile the current no-timeout editor and readable-until-screensaver requirements. The LG model and model-specific panel behavior remain unconfirmed.
- **TiviMate:** earlier research found no documented channel shortcut API; installed-app intents were not tested. HAL has ordinary app launching, not a shipped channel-favorites integration. Reverify external API claims before acting on that research.
- **Editor save durability:** success confirms application handling; SharedPreferences uses `apply()`. There is no disk-write acknowledgement in the current API. The revision is listener-scoped, not a general transaction/version system for every possible preference writer.
- **Preview fidelity:** the browser preview preserves grid placement and ordinary half-width rows but lists only the first eight Apps labels and does not reproduce live media panels, state cues, or conditional hiding. Do not present it as a pixel-identical TV preview.
- **Backup scope:** browser settings exports exclude local slideshow images, appearance, and artwork-source configuration. File exports are unencrypted and can contain reusable HA tokens, Frigate credentials, and camera passwords when selected.
- **Transport/security follow-up:** the original security review left open issues. Current code still allows cleartext integration traffic, defaults a bare HA address to HTTP, and uses first-certificate trust/pinning for Frigate with hostname verification disabled. Secrets are stored in app preferences, and the manifest has no explicit backup exclusion policy. The browser editor deliberately uses local HTTP without authentication.
- **Verification limits:** prior chats repeatedly distinguished tests/build/install from physical-TV validation. Device focus, screensaver timing, and live playback should be verified when affected; successful earlier APK installations do not close those checks automatically.

The full behavior explanations and implementation limits are in [DECISIONS.md](DECISIONS.md). Update this list when a question is resolved, with the actual evidence, instead of carrying it indefinitely as an open issue.
