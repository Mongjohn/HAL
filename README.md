# HAL

HAL (**Home Assistant Launcher**) brings apps, cameras, alerts and Home Assistant together on Android TV and Google TV.

Project repository: [Mongjohn/HAL](https://github.com/Mongjohn/HAL).

## Features

- **Cameras:** RTSP cameras in H.264 or H.265, with an optional substream for the grid. It plays as many live as your TV can decode, and shows the rest as their last picture.
- **Home Assistant:** arrange Home views with readings, lights, switches, locks, selects, actions, weather, gauges, history graphs, and media players. Set visibility rules for cards and their items. Up to three scenes or scripts can also appear as buttons in the launcher top bar.
- **Frigate alerts:** the last seven days of alerts, with clips that play full screen.
- **Apps card:** choose and arrange the Android apps on HAL Home in the web editor. Use the remote to focus and launch individual app tiles.
- **Artwork slideshow:** choose uploaded images or media artwork. Media mode uses JustWatch releases for your saved country and subscriptions, plus eligible Android TV Watch Next artwork, with up to 20 images in the rotation. No API key is required; catalog refreshes are scheduled daily.
- **Video backgrounds:** organize direct HTTP(S) videos into playlists in the web editor. Enable one or more playlists to choose the active videos for the dashboard and screensaver, with optional random starts.
- **Android TV screensaver:** select HAL Artwork in Android’s settings to show dimmed artwork or video with floating clock, weather and media bubbles across apps. Configure backgrounds and dimming in HAL’s web editor.
- **Home button helper:** optionally makes the Home button bring you to HAL. Choose one dashboard card in the web editor to receive focus.
- **Settings import and export:** save selected settings, including dashboard layouts, to a file in the browser editor and import the file on this or another HAL TV.
- **Diagnostics:** a built-in ping tool, network status, and retained logs with credential scrubbing. Download the logs from the browser editor after reopening HAL following a crash.

Cameras, Home Assistant and Frigate are all optional. HAL shows your selected apps on Home. The **Cameras** tab appears only when a camera is configured; **Alerts** appears only when Frigate is configured. Configure both in the web UI after enabling it from the Settings cog.

In **Appearance** in the web UI, choose **Glass**, **Glass Dark**, or **Calm**. Glass Dark uses a black base, deeper translucent surfaces and dimmer text. All themes display slideshow artwork at the same brightness. HAL saves the choice on the TV. The smaller Settings and network buttons align with the time above the date. When Home is the only available dashboard, its name is hidden. When Cameras or Alerts is available, all available dashboard tabs are shown. Adjust card-fill opacity from 0% to 100% in 10-point steps. Text, icons, decorative rims, and focus outlines stay visible even at 0%. **Use theme default** restores the base opacity to 50%. In the web editor’s **Appearance**, choose independent **Focused card tile opacity**, **Unfocused card tile opacity** and **Card popup opacity**, or leave each at **Use base opacity**. Focused applies to the dashboard tile/panel under remote focus; Card popup applies only to the opened Calm panel. Older settings copy the previous focused value into the new popup setting to preserve its appearance. These overrides apply to ordinary cards, Apps, media panels and Calm summaries across all themes; they do not dim text, artwork or focus outlines. Resetting the TV base does not remove explicit web overrides. Android controls screensaver activation both over HAL and over other apps.

In web **Appearance → Card popup columns**, **1 column (compact)** is the default and makes a card popup half its previous width (380 dp). **2 columns (wide)** restores the previous 760 dp width and pairs ordinary rows side by side. Weather, gauges, history graphs and dedicated media panels retain a full row, and subheadings interrupt pairs. Popup columns do not change saved dashboard layout or item-width choices.

**Calm** is a wallpaper-led presentation with the clock on the right, live card summaries along the bottom, a compact Apps area, and one focused control panel at a time. Left/Right selects a summary; OK opens its controls. Back closes the panel in one press and returns focus to its summary. Up from a summary reaches the player row when present, then the header; Down reaches Apps. All views and card contents remain available. Calm orders summaries by the saved grid position and leaves the grid, spans, row widths, names and visibility rules intact for switching back to Glass. Media-only cards appear in a full-width row directly above the summaries. The row uses Glass’s natural single-player height (84 dp) beneath the centered overview. All visible players share that row equally, with artwork, playback details, and Previous, Play/Pause, Stop and Next controls. Unsupported or unavailable controls are dimmed and do not send commands. Hidden players release or reserve their space according to their saved item rule. A small, subdued lock icon in the upper-right of a card tile shows its visible locks: green for locked, red if any is unlocked, and an uncertain/fault state when appropriate. The generic overview tagline is omitted. The browser preview continues to show the saved grid. Choose your artwork in web **Backgrounds → Slideshow artwork**; Calm does not enable the slideshow automatically.

In Calm, the center information stays in the available space above players and card tiles. It uses the normal stacked presentation when it fits, rearranges horizontally when necessary, and scales down only if neither full-size arrangement fits. The complete information group stays centered in each arrangement, including the weather icon. Apps shows one larger row at a time; Up/Down scrolls to the next configured row. A short, thin line above or below the app icons indicates a reachable neighboring row; hidden-only rows do not contribute. Icons and focus geometry keep their existing size and position. Current weather includes a subdued condition icon beside the center temperature; it stays with the overview when the information rearranges or scales. The stored app order and tiles-per-row setting are preserved. Lock badges use subdued fixed green/red colors even with **Cycle UI colors** enabled. These styling choices are not a guarantee against OLED panel wear.

**Appearance** in the web UI also has two optional settings. Both start off and are saved on the TV.

- **Collapse Apps card** has **Off**, **Up**, and **Down** choices. Up and Down collapse it when unfocused or after 30 seconds idle, leaving an **Apps** label. Moving focus back opens it on the usual tile. If it collapses while focused, the next keypress only opens it; Power works normally. An app context menu keeps it open.
- **Cycle UI colors** shifts interface hues over 10 minutes and returns over the next 10 minutes. Black stays black, card fills dim, and text stays light. App icons and banners, player and slideshow artwork, and live video keep their original colors.


## Configure HAL in the web UI

The gear menu has only **Android TV** and **Enable Web Editor**. Enable the editor, then open the exact URL shown on the TV from a desktop browser on the same local subnet. The interface supports mouse, keyboard and touch, including card dragging/resizing. The sidebar stays visible on desktop; **Find a setting** searches section names and individual options (Ctrl/Cmd+K focuses search).

| Section | Configuration |
| --- | --- |
| Dashboard | Home views, templates, cards, entities, Apps selection/order, layout and visibility |
| Backgrounds | Video library/playlists, shuffle/random starts/timing/dimming; slideshow enabled/source/country/subscriptions; image uploads |
| Appearance | Glass/Glass Dark/Calm, base/focused/unfocused/popup opacity, popup columns, Apps collapse, UI color cycling |
| Weather | Independent Off / Hourly / 5-day / Hourly + 5-day choices for dashboard overview, card popup and screensaver |
| Home Assistant | Password login with authenticator support, access-token login, logout and ordered header shortcuts |
| Cameras | Add/edit/reorder/delete cameras, main/substream URLs, transport/audio/stretch, Home camera, live grid, minimize streams, camera screensaver order/inclusion/timing/progress |
| Frigate | Address, username/password, connection verification, certificate trust/pin replacement, remove connection |
| Launcher & remote | Home interception/reopen behavior, helper consent and Accessibility status |
| Maintenance | Log retention/download/size, free-memory display, version/build information and Restart HAL |
| Settings transfer | Selected version-1 settings-file import/export |

Each settings section has its own **Save** and **Reload saved settings**. Unsaved badges remain while you navigate. Saving one section preserves other drafts. A stale or unconfirmed save retains your edits and reports what to reload. **Apply to TV**, Undo and Restore belong to the dashboard draft. Videos have their own Save/Reload; appearance and weather changes invalidate a previously loaded background revision, so reload an old video draft before overwriting newer settings.

Saved passwords, tokens and camera stream addresses are not returned in the ordinary configuration response. An empty replacement field keeps the saved credential/address for the same connection or camera; use **Remove saved substream**, **Log out**, **Remove Frigate connection** or **Delete camera** for removal. A new server or username requires its own credentials. Connect verifies the integration before saving. The password used for Home Assistant login is not stored; Frigate's password is stored on the TV. To replace a Frigate certificate, explicitly check the trust option before connecting.

**Open on TV** / **Test streams on TV** actions are available in the relevant sections. Android Accessibility permission, Watch Next permission and screensaver selection still require the TV remote. Opening Android settings can suspend the web listener until HAL returns; your browser drafts remain. Save camera streams before running a TV playback test. **Restart HAL on TV** warns before disconnecting; enable the web UI again from the gear menu or ADB after restart. HAL never writes Android screensaver/power settings or grants permissions automatically.

If HAL cannot read damaged saved camera settings, the launcher remains available and the web **Cameras** section explains how to repair them. The original data stays on the TV; saving repaired cameras preserves a private recovery copy and leaves other settings unchanged.

## Video backgrounds

Enable the web editor, open its URL, and open **Backgrounds**. In **Video library**, add a name and direct HTTP(S) file URL (MP4 or WebM), then open the video to choose **Dashboard background**, **Screensaver background**, and **Start at a random timestamp**. New flags start off. Create a named playlist under **Active playlists**, open it to select videos, and enable it. HAL uses the union of enabled playlists without duplicates, then applies each video’s screen flags. Playlist membership does not set playback order. With no enabled playlists or eligible members, HAL falls back to image artwork or black. Existing video libraries migrate into an enabled **Existing videos** playlist so their playback choices are preserved. New videos must be added to a playlist explicitly.

Keep up to 50 library videos and 24 playlists per TV. Deleting a playlist keeps the library videos; removing a video removes it from all playlists. **Playback & dimming** contains shuffle, switch interval and both background dimming controls. **Appearance** separately contains theme, base and independent focused/unfocused/popup opacity. **Save backgrounds** confirms the independent video/playback/dimming draft without applying dashboard edits. **Reload saved settings** discards its unsaved changes or recovers after an unconfirmed save. These settings remain outside settings-file transfer. **Slideshow artwork** in the same workspace still manages uploaded images.

Videos play muted and only those enabled for the current surface participate. **Shuffle videos** uses a randomized looping order; otherwise they follow saved order. **Switch video after (seconds)** sets a switch interval from 10 to 86400 seconds; 0 plays each video to its end. Shorter videos advance at their end, and a single video repeats. **Start at a random timestamp** chooses a new start when that item starts/repeats, once a seekable duration is known; live/non-seekable or unknown-duration sources start normally. Random starts leave a short tail before the end. Webpage links, DRM and adaptive playlists such as HLS/DASH are not supported by this file-based player; container/codec support depends on the TV. Links are fetched by the TV, which must be able to reach them. Query tokens are retained, but embedded username/password URLs are rejected. Video playback hides the top-right artwork caption.

One player moves its output between the dashboard and screensaver, continuing playback when their eligible playlists match. Different playlists start a new playback session on the new surface. With no eligible videos, the player is released. Background videos do not request audio focus or transport controls; Android owns screensaver activation and sleep. Bad links are skipped for that playback session; if none can play, HAL falls back to the existing artwork (or black) and retries the library at one-minute intervals. A video that cannot produce a frame within 20 seconds is skipped. **Minimize simultaneous video streams** yields the background decoder while a full-screen camera or Frigate clip is open. Video adds one decoder; check performance alongside your cameras.

Under **Playback & dimming**, set **Dashboard background dimming (%)** and **Screensaver background dimming (%)** independently from 0% (clear) to 100% (black). They affect images and videos, keeping controls and the widget readable; the default values preserve the previous gradient shading.

## Screensaver

Select **HAL Artwork** in Android TV’s screensaver settings. The web UI’s **Backgrounds → Open screensaver settings on TV** opens that screen where supported. Android controls its activation delay, app return and sleep behavior; video apps can keep their own playback visible. HAL no longer has a separate built-in idle mode or custom activation timer, and old saved HAL timeout values are ignored. The existing **HAL Cameras** screensaver remains a separate choice and existing system selections are preserved.

Configure the video library and separate dashboard/screensaver dimming in the web editor’s **Backgrounds** section, and upload images under **Slideshow artwork**. Your existing local/media artwork source still applies. The system screensaver uses the same dimmed background, clock, weather and independently bouncing media bubbles as the former built-in version, with Glass, Glass Dark and Calm. Eligible screensaver videos take priority; images or black remain the fallback. Dashboard image/crossfade state is shared, and matching video playlists retain playback through a short handoff.

Media comes from the last selected Home view, including mixed cards, paused/off players, friendly-name overrides and live visibility rules. A missing view falls back to the first view. Hidden/reserved rows and hidden cards are excluded, and duplicate players appear once. Each player has an informational bubble with artwork, playback metadata and progress; there are no transport controls. Media bubbles retain their saved card/entity order while bouncing. Clock and media bubbles keep their existing fitting and full-display movement. Movement and dimming are not a guarantee against OLED wear.

Image artwork shows its title, service and available release year in the screensaver’s upper-right corner, using HAL’s small caption style; ready video hides this caption and image fallback restores it.

The clock follows the TV’s language and 12/24-hour setting. Weather uses an available weather entity from your configured Home Assistant dashboard first, then another available Home Assistant weather entity. With multiple sources, the fallback is ordered by entity ID. HAL follows weather updates even when no weather card is displayed. Without a connected, usable weather source, the card says **Weather unavailable**. Extended weather is optional and defaults to **Off**. In web **Weather**, independently select **Off**, **Hourly** (up to six hours), **5-day** or **Hourly + 5-day** for **Dashboard overview**, **Card popup** and **Screensaver**, then Save. Dashboard covers weather cards and Calm’s weather overview; Card popup covers the panel opened by pressing a Calm summary tile. On upgrade, the popup inherits the previous dashboard forecast choice, then changes independently. Forecasts have no visible section headings. A divider and spacing separate them from current conditions; **Hourly + 5-day** stacks hourly above daily with a subtle divider between rows. If only one type is available, combined mode shows that row without an empty section. The 5-day choice shows available dates with condition icons and high/low temperatures, starting today when supplied. Dates use the TV’s timezone and weekday labels its locale. Past, duplicate and invalid dates are excluded; providers with fewer days show fewer. No new location service or weather account is required.

Android dismisses the system screensaver on input and decides which app to restore. HAL Helper consumes a Home wake press through repeats/release so it does not also open HAL or change the focused card. Power and sleep remain system actions. The launcher’s composed dashboard, menus, forms and selection are retained while Android covers it; Android can still reclaim its process. The system service can start without the launcher and loads its saved configuration independently.

An already enabled HAL web editor remains reachable during HAL Artwork, including when it starts over another app, as long as its launcher session is still alive. It is not an always-on server: cold-starting the screensaver does not enable the editor. Dismissing the screensaver back to another app suspends the listener again.

## When HAL is hidden

When another app covers HAL, HAL suspends recurring UI work and network subscriptions, releases its live camera players and releases background video after a bounded 750 ms paused handoff, pauses Frigate playback and cancels artwork jobs. The shared Home Assistant connection also disconnects after a 750 ms handoff when neither the launcher nor artwork screensaver is visible. The enabled web editor becomes temporarily unavailable and resumes when HAL returns; HAL Artwork is the exception described above. Artwork refresh and live weather/media also run while that system screensaver is visible. HAL keeps the dashboard, focus and cached artwork ready for a Home press; optional app/artwork/editor refresh follows the first dashboard frame. The Home helper remains available. To prioritize a quick return, a paused Frigate clip retains its prepared player and may finish bounded buffering. This reduces background work rather than guaranteeing zero RAM use; Android controls process retention.

## Privacy

HAL has no HAL account or analytics service. It connects to your configured integrations and artwork sources. Media mode fetches JustWatch pages and images for your selected services and can load artwork URLs supplied by Android TV apps. Media-player cards can also load artwork supplied by Home Assistant. Video backgrounds fetch the direct links saved in the TV’s separate video library without forwarding Home Assistant credentials.

The TV stores integration credentials. The web editor uses local HTTP without pairing while enabled. Its ordinary dashboard and settings-selection responses omit credentials. Selecting camera or integration login settings for file export can include reusable credentials in an unencrypted file. HAL also supports cleartext integration URLs. A Home Assistant address without a scheme defaults to HTTP.

## Edit dashboards

On the TV, open the Settings cog and turn on **Enable Web Editor**. Scan the QR code or open the exact displayed HTTP address on port 8765 from a desktop browser with mouse, keyboard or touch on the same local subnet. The editor opens on **Dashboard**, with Home selected. Use the sidebar and settings search to find configuration; **Backgrounds** holds images/video/dimming, **Appearance** holds themes/opacity, and **Settings transfer** keeps import/export separate. Switching sections preserves independent drafts. **View layout & template** holds view sizing and template options; view switching and Add card stay beside the preview. Assistant has been retired; its saved layouts migrate to additional Home views labeled **Imported · …**. A Home Assistant dashboard can be used as a starting template: HAL copies supported content and reports skipped content. Later layout edits in HA do not change that copy.

- **Arrange:** drag a card by its title or grip, or resize it from the lower-right corner. The Layout section also offers position and size fields. Views support up to 24 total columns and six columns on screen; Auto shows up to six. The 16:9 preview scrolls wider and taller layouts inside its fixed viewport. The TV viewport fits 15 grid rows, and an individual card is limited to that height.
- **Contents:** choose entities using search, Room and Type filters, or manual IDs. Expand **Friendly name & row settings** to rename an entity throughout Home or add a subheading. Clear headings to hide them. Adjacent ordinary items marked **Half width** share a row; weather, gauges, graphs, and dedicated media panels stay full width.
- **Visibility:** use the card's top-level section for the whole card, or an item's own disclosure for a row or app tile. Enter raw HA states such as `on` or `playing`, without quotes. Combine **Is any of** and **Is none of** with nested groups. Hidden items can release their space or leave an empty slot. Hidden whole cards keep their grid positions. Conditional items remain editable in the browser preview.
- **Apps:** select and order apps in the Home-only Apps card and set tiles per row. **All apps** opens the app drawer. Under Layout, **Focus on Home press** can designate one card across all Home views.

HAL refreshes the app list when apps are installed, updated, removed or enabled/disabled. Your saved selection and order stay separate from the installed-app list; returning Home reuses an unchanged catalog.

**Apply to TV** saves Home and records its previous layout. **Restore previous Home layout** consumes that restore point and replaces the open draft. Browser Undo still edits the draft. If HAL’s confirmation is lost, the draft stays open and the TV may already have received the change.

Closing the QR popup leaves the editor enabled. The listener has no idle timeout while HAL or HAL Artwork is visible; it suspends while another app covers HAL without that screensaver and resumes on return. Toggle **Enable Web Editor** off in the cog menu to stop the listener. It remains tied to the launcher session and starts disabled after a fresh app launch. HAL and its dialogs allow Android’s selected screensaver to start normally.

Open **Backgrounds → Slideshow artwork** in the web editor to upload or delete up to 20 JPEG, PNG, or WebP images, each at most 8 MB and 8192 pixels wide or tall. Changes take effect without Apply to TV. HAL stores them in its private `files/slideshow-artwork` directory. In **Backgrounds → Slideshow artwork**, turn on **Artwork slideshow** and **Use uploaded images**, then Save. Turn off **Use uploaded images** to use media artwork and configure country/subscriptions. If the selected source has no usable image, the background stays black and the dashboard remains visible.

The rotation is shuffled, with the last shown image deferred when another can load. Captions show title and service, plus the original release year when available. Local captions use the sanitized filename without its extension. Daily media refresh runs only while HAL or HAL Artwork is visible and depends on Android scheduling/network availability. Due work resumes after returning to HAL. A service selection filters the catalog; it does not verify your account or guarantee a title can play.

### Enable the web editor over ADB

After connecting ADB, this explicit activity action opens HAL and enables the editor, whether HAL is already running or starting cold:

```powershell
adb -s 10.1.0.130:5555 shell am start -n com.evofactory.hal/.MainActivity -a com.evofactory.hal.ENABLE_WEB_EDITOR
```

The address/QR popup appears on the TV. The command uses the office SHIELD; use the intended device’s explicit serial for another TV. It enables the same session-only listener as the cog toggle, with the same local-network request checks. It does not make the editor persistent across fresh launches or change Android’s screensaver settings. Cold ADB activation and the editor’s HTTP response were verified on the office SHIELD on 2026-11-23; repeating the action was delivered successfully to the running activity.

## Import and export settings

In the browser editor, open **Settings transfer → Import / export settings**.

- **Export** using **Choose settings to export**. Check the settings you want, then select **Download settings file** to save `hal-settings.json`. The file reflects saved TV settings. Apply any dashboard edits you want included before downloading.
- **Import** using **Import file**. Open a HAL version 1 settings JSON file, review the choices, then select **Import selected settings to TV** and confirm. Imported dashboard layouts replace Home. Older files containing only Assistant become Home; files containing both append Assistant views to the file’s Home layout before replacement. Home’s existing friendly-name overrides take priority when the old surfaces name the same entity differently. Camera and entity selections merge with the TV's existing choices, and selected logins replace the saved logins. A confirmed import reloads the Home draft from the TV and discards unapplied edits.

To copy settings to another HAL TV, open that TV's browser editor and import the downloaded file. Camera and login choices start unchecked because they can include credentials. Uploaded slideshow images, appearance, and artwork-source settings are outside the file's scope.

## Use the remote

Move between ordinary cards with the D-pad and press OK to enter their controls. Light icons keep Home Assistant’s reported RGB color while on and stay outside UI color cycling. On a Calm summary, identical on-light colors are retained; mixed on-light colors use the theme’s normal on color. Off/unknown/unavailable lights do not supply a stale color. Stateful rows use icons to show their state, and OK runs a supported primary action such as toggling a light or locking or unlocking a lock. Select rows open a choice menu. Media-only cards show player panels. Increase card height in the editor to grow the artwork, with width reserved for labels. Taller panels also show more metadata and playback progress. Progress uses a rounded gradient track and a subtle illuminated playhead; it retains the existing monotonic timing and freezes on pause without a separate animation timer.

Entering the Apps card focuses a tile directly. OK launches it. Normal navigation returns to the last available tile; a Home press handled by HAL's helper selects the first visible tile when Apps is the Home-focus target. The Home handoff focuses the target directly without briefly selecting the header. At an edge, navigation moves to a neighboring card when available; a dead left/right edge keeps focus on the current tile.

When the designated Home-focus card is hidden or unavailable, a Home press handled by HAL returns to the header. When Home is the only tab, its label is not focusable; the network button is the header fallback.

On remotes with color buttons, red opens Home, green Cameras, and yellow Alerts, when that tab is available. The retired blue Assistant shortcut has no HAL action. Number buttons select cameras, and channel up/down changes cameras on Home or Cameras. The dot button focuses Settings.

## Requirements

An Android TV or Google TV device on Android 9 or later. The current development reference is an NVIDIA SHIELD TV with an LG OLED display. The original project also reported testing on Sony BRAVIA devices with Android 9 and Google TV 14; that is historical coverage, not verification of every current feature on those models.

## Install the APK

Download the `.apk` file from the [latest release](../../releases/latest), then install it in one of these two ways.

### With a computer

This needs [adb](https://developer.android.com/tools/releases/platform-tools) on your computer, with the TV and computer on the same network.

1. On the TV, open **Settings → Device Preferences → About** and press OK on **Build** seven times to turn on developer options. The menu names vary by TV.
2. In **Settings → Device Preferences → Developer options**, turn on **USB debugging**. On Google TV 14, you may need **Wireless debugging** instead, which shows the address and port to use.
3. Connect, using your TV's IP address. Accept the prompt on the TV if one appears:

   ```bash
   adb connect TV_IP_ADDRESS:5555
   ```

4. Check `adb devices -l`, verify the intended TV, then install the APK using its listed serial/address:

   ```bash
   adb -s TV_SERIAL install -r HAL.apk
   ```

Use the name of the file you downloaded in place of `HAL.apk`.

### Without a computer

1. Copy the APK to the TV with a file manager app, or download it with a browser app.
2. When Android asks, allow that app to install unknown apps.
3. Open the APK and choose **Install**.

### Afterwards

HAL appears in your TV's apps. To make the Home button open it, enable the web UI from the cog, choose the Home behavior in **Launcher & remote**, and turn on **HAL Helper** in your TV's Accessibility settings. The helper is optional, and if HAL fails to start a few times in a row, it switches itself off.

To update HAL, install a newer APK with the same signing key. Your settings stay in place. Builds that used the previous Android package ID install separately from HAL and require a fresh setup.

## Build from source

For architecture-consolidation progress and remaining device verification, see the [implementation status](docs/review/IMPLEMENTATION_STATUS.md). Contributors can collect a non-interrupting office-SHIELD diagnostic snapshot with `powershell -File tools/Measure-Hal.ps1 -Mode Snapshot`; results stay under the ignored `build/performance/` directory. Controlled timing modes require `-AllowInterruption` and a dedicated test session. The optional `benchmark` build type uses release optimization with the local debug key and shell profiling; APK assembly/installation still needs an explicit request. Do not infer speed improvements from unit tests or historical frame counters.

Open this folder in Android Studio, or use the Gradle wrapper with the required Java 25 toolchain and Android SDK (compile SDK 37). To build a debug APK:

```bash
./gradlew assembleDebug
```

The APK is in `app/build/outputs/apk/debug/`. Android accepts in-place updates only with the original signing key; a self-signed build cannot update a release signed with another key. For an existing HAL TV, recover the matching key rather than uninstalling HAL or clearing its data to work around a signing mismatch.

The release build looks for its signing key outside this repo, and comes out unsigned if it isn't there. When a release key exists, the current signing configuration retrieves its password through macOS Keychain.

For contributor setup, the existing Windows toolchain, test commands, source map, verification limits, and cross-chat documentation workflow, read [the development handoff](docs/DEVELOPMENT.md). [Behavior and decisions](docs/DECISIONS.md) explains the reasons behind the dashboard, remote, editor, visibility, artwork, and persistence behavior. Agents should build an APK only when explicitly asked.

Runtime dependencies include a targeted Guava security constraint; keep its advisory rationale when updating Media3. The [dependency follow-up](docs/review/10_VALIDATION_FOLLOWUP.md) and [device validation](docs/review/11_DEVICE_VALIDATION.md) record its source checks, patched installation and remaining device/distribution checks.

The [optimized validation](docs/review/12_OPTIMIZED_VALIDATION.md) records the newer installed build, mixed startup/focus/memory measurements and targeted build-tool dependency fixes. Reusable inventory and device-test commands are in the development handoff. After force-stop or benchmark/package-replacement tests, verify HAL Helper in Android Accessibility: Android can turn it off, and restoring the APK alone may not restore Home handling. Restore only its previous choice through Android settings.

Start each new HAL chat by reading the project instructions and handoff. Before committing or merging changes into `main`, review the affected documentation and include any needed updates with the changes. Follow the [development handoff](docs/DEVELOPMENT.md#keeping-work-understandable-across-chats) for the full workflow.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

Copyright 2026 Evo Factory S.à r.l.
