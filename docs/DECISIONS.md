# HAL behavior and decisions

This is the behavior contract and rationale for ongoing development. Read it with [the development handoff](DEVELOPMENT.md) and [the user guide](../README.md). Source references below are relative to `app/src/main/java/com/evofactory/hal/` unless stated otherwise. The 2026-11-16 review used `0fa2ee2` as its baseline and was updated for `codex/browser-settings-and-restore`.

## Native launcher and configuration ownership

HAL is a native Kotlin/Compose Android TV launcher, not a website displayed on a TV. Its Android package is `com.evofactory.hal`. Home Assistant, RTSP cameras, and Frigate are optional. Home remains useful as an app launcher without them.

The browser editor owns dashboard layout, content, names, visibility, and all settings import/export. The TV settings own integration setup, top-bar actions, launcher behavior, appearance, and artwork source. This keeps layout editing and file handling accessible from a phone or computer. The editor is bundled in the APK so it works without a hosted editor service.

Copying a Home Assistant dashboard creates an independent HAL layout. It does not modify the HA dashboard or keep layout changes synchronized. Entity states still update live. Legacy display settings and the old app-selection keys remain as migration inputs; their presence in code is not a reason to restore the removed TV layout menus or dock.

Sources: `MainActivity.kt`, `LauncherPrefs.kt`, `DashboardEditor.kt`, `DashboardLayout.kt`, `HomeAssistantDialog.kt`, `LauncherSettingsDialog.kt`.

## Grid, contents, and remote navigation

Each surface has views. A view has 1–24 total columns and 1–6 columns on screen; **Auto** shows all columns up to six. Explicit positions and spans survive horizontal scrolling. The viewport has 15 grid rows; increasing grid resolution must not increase the dashboard's physical height. Cards are at most one viewport tall and scroll their content internally. Views can extend vertically beyond that viewport. The Home camera remains outside the editable card grid in its fixed position, and its area is reclaimed when absent.

These choices preserve a deliberate arrangement and make the preview useful for TV placement. Do not compact whole-card gaps when visibility changes. Left/Right navigation searches across gaps and brings off-screen targets into view. Changing views resets both scroll directions. Keep normal header spacing and the clock's screen inset as the reference for outer margins.

Card titles and subheadings are optional. A subheading belongs before an item or after the final item; clearing it removes the occupied heading space. Ordinary rows can be half width, pairing adjacent eligible items unless a heading or full-width row interrupts them. Weather, gauges, graphs, and dedicated media panels remain full width. The saved `twoPerRow` field is a fallback for older layouts; explicit per-item widths take precedence.

Ordinary cards have two navigation levels: focus the card, then press OK to enter its controls. **Apps** is the exception: entering it focuses an app tile immediately, and OK launches it. There is at most one Apps card, across all Home views, and none on Assistant. It inherits the old dock selection and order, retains **All apps**, and fits artwork without cropping. At a tile boundary, move to a neighboring card when one exists; dead side edges retain the current tile. Preserve the Up route to the header.

Normal navigation remembers the last available app tile. A handled remote Home press deliberately starts at the first visible tile in grid order instead. **Focus on Home press** is unique across both surfaces and all views. Assigning a new target clears the old one. HAL checks live card visibility before routing to Assistant and checks again after switching the view. A hidden, absent, or unfocusable target falls back to normal Home focus. The request is consumed after the attempt so a later visit to Assistant or Home uses ordinary navigation.

**Cameras** exists only with configured cameras; **Alerts** exists only with configured Frigate. Their color shortcuts have the same availability checks. If the current tab disappears, return to Home. Setup remains reachable from the cog so removing an empty tab never removes the path to configure it.

Sources: `DashboardLayout.kt`, `HomeAssistantTab.kt`, `DashboardAppsCard.kt`, `MainActivity.kt`, `AppRow.kt`, `RemoteButtons.kt`. Tests: `HaCardLayoutTest.kt`, `DockTileKeysTest.kt`.

## State cues and actions

Use icons and restrained colors to make state readable from a TV viewing distance: warm amber for active lights, green for locked, red for unlocked. Omit duplicate visible state text or switches only when the icon already communicates that state. Keep numeric values, units, and unfamiliar states readable. A simpler appearance must not remove remote OK behavior.

Choose a primary action from the latest HA state when activated, with feature checks where the domain advertises them. Unknown, unavailable, and unsupported states produce no primary service call. Locks alternate lock/unlock; supported covers and valves open/close; media players choose pause, play, or power from state and capabilities; vacuums prefer return-to-base over stop while cleaning. Selects retain their menus, and scenes, scripts, and buttons retain their action paths. A recognized entity type does not imply HAL implements every control it exposes in HA.

Cards configured exclusively with standard media-player readings get one full-width panel per player and no containing frame. Mixed cards use ordinary rows for all items, including players. This classification follows configured contents, not whichever rows happen to be visible at a moment. At card-level focus, highlight all visible player panels; after OK, highlight only the selected player. Short cards use the compact presentation; taller cards expose more metadata and progress. The panel offers the supported primary action, not a full HA transport or seek interface.

Playback progress uses a monotonic clock between HA updates because position reports may be sparse. A fresh usable timestamp anchors the sample; otherwise use receipt time. Unrelated attribute changes must not reset progress. Pause freezes it; new position or track samples resynchronize it. Media artwork sends HA authorization only to the configured origin (scheme, host, and port); external artwork must use HTTPS without that token, and redirects are disabled for this request path.

Sources: `HaStateCue.kt`, `HaPrimaryAction.kt`, `HaMediaPlayer.kt`, `HomeAssistant.kt`, `HomeAssistantTab.kt`. Tests: `HaStateCueTest.kt`, `HaPrimaryActionTest.kt`, `HaMediaPlayerTest.kt`.

## Conditional visibility

Rules can belong to a whole card, an entity row, or an Apps tile. HAL-authored comparisons use exact, case-sensitive HA state strings, with **Is any of** or **Is none of**. Group operations are **All conditions match**, **Any condition matches**, and **At least one condition fails**. The last is NOT(AND), not “none match.” A missing entity produces an indeterminate result for a HAL-authored rule. Show content only when the result is definitely true; a decisive false in All or true in Any can settle a group even if another input is missing. Literal `unknown` and `unavailable` values received from HA can themselves be compared.

Imported HA rules retain their separate comparison semantics: entity-looking comparison values can reference another entity, and a missing subject is treated as `unknown`. This distinction prevents importing a rule from silently changing its meaning. Supported imports include legacy state syntax, state/state_not, nested and/or/not, conditional wrappers, and per-card visibility. Attribute, duration, numeric, screen, user, and other unsupported conditions are not general-purpose supported rule types. A card with invalid or unsupported visibility is skipped with an import notice rather than made unconditionally visible.

Subscribe to rule-only entities and comparison entities even when they appear nowhere in card contents. The first subscription snapshot after reconnect replaces stale membership; otherwise removed entities could keep old visibility decisions alive. Flattening HA sections must preserve each conditional scope, without applying a nested condition to unrelated siblings.

Hidden whole cards retain their grid positions. Hidden items default to **Release space**, allowing later items to reflow; **Keep space empty** preserves their slots. Hidden items never remain navigation targets. Row rules follow configured row indices when reordering, including gauge and graph entries. App rules follow app IDs. Delete the rule with its item. Optional saved fields keep older layouts loadable.

The browser keeps conditional content editable; it is a layout preview, not a live HA-state simulator or a pixel-identical TV rendering. It shows entity labels and half-width row placement; Apps previews currently list only the first eight app labels rather than rendering the TV tile grid, and media artwork/controls are not reproduced. Removing the last rule/group clears visibility. Incomplete rules take the user to the relevant card or item disclosure before Apply can succeed.

Sources: `CardVisibility.kt`, `HaDashboard.kt`, `HomeAssistant.kt`, `HomeAssistantPanel.kt`, `DashboardLayout.kt`, `HomeAssistantTab.kt`, browser `editor.js`. Tests: `CardVisibilityTest.kt`, `HaCardLayoutTest.kt`.

## Browser session and saving

The Settings cog order is HAL, Home Assistant, Cameras, Frigate, Android TV, Enable Web Editor. The toggle starts HTTP on port 8765 and opens a URL/QR popup. Closing that popup leaves the listener running; switching the toggle off stops it. There is no idle timeout. The running listener keeps HAL's foreground view awake, because screensaver activation previously interrupted editing. This is session state, not a persisted always-on server: activity/process teardown closes it and a fresh launch starts disabled. It does not lock the user into HAL or promise background-service availability.

No pairing is a deliberate local-network usability choice. The listener binds a selected Wi-Fi/Ethernet IPv4 address, restricts peers to that interface's subnet, checks Host/Origin and cross-site headers, and limits request sizes. The editor uses unencrypted HTTP. State, catalog, and settings-choice responses omit credentials. An explicit settings-file export can return selected logins or camera URLs with credentials. Use the displayed IP address and restart editing after an address change if the old URL stops working.

**Apply to TV** sends the selected surface with the listener's revision. A stale revision is rejected, rather than overwriting a newer editor save. Success requires an explicit `applied` response and a new revision; an unconfirmed response retains the browser draft. A lost response can still follow a successful TV update, so “unconfirmed” does not mean “definitely unsaved.” The current implementation updates in-memory state and queues a SharedPreferences write; it does not guarantee a completed disk flush.

Each Apply saves a restore point for the selected surface. The first Apply can save the effective layout seeded from legacy settings. Saving one surface keeps the other surface's restore point and leaves any legacy layout on that surface unmaterialized. Existing paired snapshots migrate into separate restore points. Restore consumes the selected point and replaces that surface's draft. Browser Undo applies to draft edits. Apply and Restore share normalization and the global Home-focus rule. If the selected layout designates a target, it takes priority over the other surface's target, including that flag in the other surface's draft and Undo entries.

The user chose the browser as the single import/export workflow. The TV-to-TV protocol, discovery permission, and dialogs were removed. Copy settings between TVs by exporting a file in one TV's editor and importing it in the other's.

The browser exports selected saved TV settings in version 1 HAL JSON. It previews the file's selectable contents before applying changes. Camera and integration login choices start unchecked because their payloads can contain credentials. Cameras and entity selections merge, selected logins replace their saved values, and imported dashboards replace the included surfaces. Import clears the restore point for each layout it changes. Confirmed imports reload both browser drafts from saved state, discarding unapplied edits, and refresh the Home Assistant catalog. A reload or catalog failure after confirmation is reported separately. The UTF-8 JSON request body for preview or import must fit within 4 MiB, including the wrapper and any selection keys.

The versioned file covers selected layouts, app settings, cameras, entity settings, and integration logins. Earlier version 1 files still import. A legacy app-tile list can update the Home Apps card when the file selection supplies no Home layout. Uploaded artwork, appearance, and artwork-source settings are outside the file's scope.

Drag steps use rendered track widths, gaps, and scroll deltas so diagonal moves and narrow previews remain accurate. Drag title/grip; resize at the lower-right handle. Swap only when both cards fit. Changing Column in the sidebar tries the current row, then the nearest free row in that column, leaving other cards in place. The 16:9 preview keeps its footprint when empty and scrolls overflow inside it.

Use the same keyboard-friendly entity picker in Contents and Visibility. Room and Type narrow the result set; every case-insensitive search word must match somewhere in the name or ID, in any order. Friendly-name overrides apply throughout one surface, not globally in HA. Keep secondary row settings collapsed and whole-card versus item visibility clearly separated.

JSON requests are explicitly decoded as UTF-8, including requests without a charset. This prevents new label corruption but cannot recover replacement characters already saved. Compare overrides against the HA catalog and repair or clear the affected labels, then save/read back. LastPass errors previously seen in `8.packages.js` and `30.infield.js` belong to the extension; use a clean browser profile to establish ownership before changing HAL.

Sources: `DashboardEditor.kt`, `LauncherPrefs.kt`, `MainActivity.kt`, `ExportImport.kt`, `app/src/main/assets/dashboard-editor/`.

## Artwork, appearance, and idle behavior

Artwork is opt-in. **Use local artwork** selects a source explicitly: on means uploaded files, off means media artwork. Do not switch sources implicitly when local files are added or removed. If no selected image can load, use black with the dashboard still visible. If a current eligible image loaded and other images fail, the rotation can retain it.

Media artwork uses JustWatch regional release pages without an API key, filtered by saved country and selected subscriptions. “Recent” refers to arrival on a streaming service, while the caption year is the title's original release year. Titles are deduplicated and capped at 20. A title-page backdrop is preferred, with a poster fallback. Android schedules a network-required refresh roughly every 24 hours and checks freshness on startup/settings changes; this is not an exact-time job. Local mode or a disabled slideshow cancels catalog jobs. Matching cached metadata and cached images can help through outages but do not guarantee offline artwork.

Watch Next supplies eligible images from supported apps in the launcher app selection. Explicitly paid entries are excluded; unknown availability is not proof of a subscription. The merged rotation is **catalog first, then Watch Next**, deduplicated by URI and capped at 20; a full catalog excludes Watch Next entries from that pass. Conditional hiding of an Apps tile does not currently filter the separate artwork app-selection list. No generic unrelated-artwork fallback is used.

Local uploads allow 20 JPEG/PNG/WebP files, each at most 8 MiB and 8192 pixels in either dimension. Validate actual decodable image content, confine file operations to private `files/slideshow-artwork`, reject symlinks, and use generated IDs. Filenames are sanitized to ASCII stems; captions omit the generated ID and extension. Upload/delete takes effect independently of Apply to TV. Local images are not included in settings exports.

The rotation shuffles at startup and between passes, remembers the last image, and defers it when another can load. Slides stay about 45 seconds with a 1.2-second crossfade, behind a dark overlay. This varies the artwork without hiding the dashboard or preventing Android's ordinary screensaver handoff. Watch Next's former dashboard row and chevrons are removed.

The user kept Glass as the sole theme and retained **HAL settings → Appearance**. The theme row displays Glass and the opacity controls remain interactive. HAL uses the Glass palette on upgrade regardless of the old theme choice. A saved card-fill opacity override carries over, including 0% and 100%. **Use theme default** clears the override and restores 50%. Opacity changes fills only. Text, icons, focus outlines, and Glass's decorative border remain visible even at zero. Glass uses gradients and highlights. The camera screensaver uses the shared Glass palette for its text.

HAL's optional camera screensaver is a separate Android DreamService selected in system settings. It requests a bright screen, rotates available cameras in order, retains a camera-name overlay, and can leave a single camera or an empty-state message on screen. The 2026-11-16 OLED discussion proposed softer Glass styling and changes to idle/editor/screensaver behavior; those remain research proposals, not shipped behavior or permission to change the no-timeout editor contract. The reference setup is SHIELD plus LG OLED; historical Sony notes are not that user's display model.

Sources: `ArtworkCatalog.kt`, `ArtworkRefreshJob.kt`, `JustWatchReleases.kt`, `DashboardArtwork.kt`, `LocalArtwork.kt`, `WatchNext.kt`, `Theme.kt`, `Screensaver.kt`. Tests: `JustWatchReleasesTest.kt`, `DashboardArtworkTest.kt`, `WatchNextTest.kt`.

## Other integrations and diagnostics

Cameras use Media3 RTSP with optional grid substreams. Live slots depend on device decoder capacity; snapshots cover cameras that are not live. Do not equate successful APK installation with successful hardware decoding. Frigate shows alerts from the last seven days and full-screen clips. Camera snapshots and logs are local retained data, distinct from uploaded slideshow files.

The network icon describes Android's default connection and usable addresses; it is not a Home Assistant health indicator or proof of internet access. Build status from capabilities and link properties for the same callback network, waiting for both and ignoring replaced-network events. Synchronous connectivity reads inside callbacks previously produced a false disconnected icon.

**Download HAL logs** uses `/api/logs` under the same editor listener checks and returns `hal-logs.txt`. Retention defaults to 12 hours and is configurable from 1–72 hours. Logs include recorded crash traces and use credential scrubbing; do not promise that every private value is removed. Reopen HAL and enable the editor after a crash to retrieve them.

The Home helper is optional and requires Android Accessibility permission plus a HAL override setting. Catching Home avoids the stock launcher's delay but can affect held volume-key repeat; reopening after the stock launcher appears is a separate option. Repeated failed launches switch the override off to preserve access to the device. This is not automatic default-launcher registration on every TV.

Sources: `Cameras.kt`, `CameraView.kt`, `StreamDescription.kt`, `StuckPlayers.kt`, `Frigate.kt`, `FrigateAlerts.kt`, `NetworkStatus.kt`, `AppLog.kt`, `HalApp.kt`, `HomeOverrideService.kt`.
