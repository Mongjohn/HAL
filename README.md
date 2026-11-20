# HAL

HAL (**Home Assistant Launcher**) brings apps, cameras, alerts and Home Assistant together on Android TV and Google TV.

Project repository: [Mongjohn/HAL](https://github.com/Mongjohn/HAL).

## Features

- **Cameras:** RTSP cameras in H.264 or H.265, with an optional substream for the grid. It plays as many live as your TV can decode, and shows the rest as their last picture.
- **Home Assistant:** arrange Home views with readings, lights, switches, locks, selects, actions, weather, gauges, history graphs, and media players. Set visibility rules for cards and their items. Up to three scenes or scripts can also appear as buttons in the launcher top bar.
- **Frigate alerts:** the last seven days of alerts, with clips that play full screen.
- **Apps card:** choose and arrange the Android apps on HAL Home in the web editor. Use the remote to focus and launch individual app tiles.
- **Artwork slideshow:** choose uploaded images or media artwork. Media mode uses JustWatch releases for your saved country and subscriptions, plus eligible Android TV Watch Next artwork, with up to 20 images in the rotation. No API key is required; catalog refreshes are scheduled daily.
- **Home button helper:** optionally makes the Home button bring you to HAL. Choose one dashboard card in the web editor to receive focus.
- **Settings import and export:** save selected settings, including dashboard layouts, to a file in the browser editor and import the file on this or another HAL TV.
- **Diagnostics:** a built-in ping tool, network status, and retained logs with credential scrubbing. Download the logs from the browser editor after reopening HAL following a crash.

Cameras, Home Assistant and Frigate are all optional. HAL shows your selected apps on Home. The **Cameras** tab appears only when a camera is configured; **Alerts** appears only when Frigate is configured. Both remain available to configure from the Settings cog.

In **HAL settings → Appearance**, choose **Glass**, **Glass Dark**, or **Calm**. Glass Dark uses a black base, deeper translucent surfaces and dimmer text. All themes display slideshow artwork at the same brightness. HAL saves the choice on the TV. Adjust card-fill opacity from 0% to 100% in 10-point steps. Text, icons, decorative rims, and focus outlines stay visible even at 0%. **Use theme default** restores 50%. Saved opacity applies to all themes. Android's screensaver can take over during ordinary dashboard use, including when the artwork slideshow is running.

**Calm** is a wallpaper-led presentation with the clock on the right, live card summaries along the bottom, a compact Apps area, and one focused control panel at a time. Left/Right selects a summary; OK opens its controls. Back closes the panel in one press and returns focus to its summary. Up from a summary reaches the player row when present, then the header; Down reaches Apps. All views and card contents remain available. Calm orders summaries by the saved grid position and leaves the grid, spans, row widths, names and visibility rules intact for switching back to Glass. Media-only cards appear in a full-width row directly above the summaries. The row uses Glass’s natural single-player height (84 dp) beneath the centered overview. All visible players share that row equally, with artwork, playback details, and Previous, Play/Pause, Stop and Next controls. Unsupported or unavailable controls are dimmed and do not send commands. Hidden players release or reserve their space according to their saved item rule. A small, subdued lock icon in the upper-right of a card tile shows its visible locks: green for locked, red if any is unlocked, and an uncertain/fault state when appropriate. The generic overview tagline is omitted. The browser preview continues to show the saved grid. Choose your artwork in **Background**; Calm does not enable the slideshow automatically.

In Calm, the center information stays in the available space above players and card tiles. It uses the normal stacked presentation when it fits, rearranges horizontally when necessary, and scales down only if neither full-size arrangement fits. Apps shows one larger row at a time; Up/Down scrolls to the next configured row. The stored app order and tiles-per-row setting are preserved. Lock badges use subdued fixed green/red colors even with **Cycle UI colors** enabled. These styling choices are not a guarantee against OLED panel wear.

**HAL settings → Appearance** also has two optional settings. Both start off and are saved on the TV.

- **Collapse Apps card** has **Off**, **Up**, and **Down** choices. Up and Down collapse it when unfocused or after 30 seconds idle, leaving an **Apps** label. Moving focus back opens it on the usual tile. If it collapses while focused, the next keypress only opens it; Power works normally. An app context menu keeps it open.
- **Cycle UI colors** shifts interface hues over 10 minutes and returns over the next 10 minutes. Black stays black, card fills dim, and text stays light. App icons and banners, player and slideshow artwork, and live video keep their original colors.

## Privacy

HAL has no HAL account or analytics service. It connects to your configured integrations and artwork sources. Media mode fetches JustWatch pages and images for your selected services and can load artwork URLs supplied by Android TV apps. Media-player cards can also load artwork supplied by Home Assistant.

The TV stores integration credentials. The web editor uses local HTTP without pairing while enabled. Its ordinary dashboard and settings-selection responses omit credentials. Selecting camera or integration login settings for file export can include reusable credentials in an unencrypted file. HAL also supports cleartext integration URLs. A Home Assistant address without a scheme defaults to HTTP.

## Edit dashboards

On the TV, open the Settings cog and turn on **Enable Web Editor**. Scan the QR code or open the exact displayed HTTP address on port 8765 from a phone or computer on the same local subnet. The editor opens on **Home panel**. Assistant has been retired; its saved layouts migrate to additional Home views labeled **Imported · …**. A Home Assistant dashboard can be used as a starting template: HAL copies supported content and reports skipped content. Later layout edits in HA do not change that copy.

- **Arrange:** drag a card by its title or grip, or resize it from the lower-right corner. The Layout section also offers position and size fields. Views support up to 24 total columns and six columns on screen; Auto shows up to six. The 16:9 preview scrolls wider and taller layouts inside its fixed viewport. The TV viewport fits 15 grid rows, and an individual card is limited to that height.
- **Contents:** choose entities using search, Room and Type filters, or manual IDs. Expand **Friendly name & row settings** to rename an entity throughout Home or add a subheading. Clear headings to hide them. Adjacent ordinary items marked **Half width** share a row; weather, gauges, graphs, and dedicated media panels stay full width.
- **Visibility:** use the card's top-level section for the whole card, or an item's own disclosure for a row or app tile. Enter raw HA states such as `on` or `playing`, without quotes. Combine **Is any of** and **Is none of** with nested groups. Hidden items can release their space or leave an empty slot. Hidden whole cards keep their grid positions. Conditional items remain editable in the browser preview.
- **Apps:** select and order apps in the Home-only Apps card and set tiles per row. **All apps** opens the app drawer. Under Layout, **Focus on Home press** can designate one card across all Home views.

**Apply to TV** saves Home and records its previous layout. **Restore previous Home layout** consumes that restore point and replaces the open draft. Browser Undo still edits the draft. If HAL’s confirmation is lost, the draft stays open and the TV may already have received the change.

While the web editor is enabled and HAL is in the foreground, HAL keeps the screen awake so Android's screensaver stays off. Closing the QR popup leaves the editor enabled. There is no idle timeout. Toggle **Enable Web Editor** off in the cog menu to stop the listener and restore normal idle behavior. The listener runs with the HAL activity and starts disabled after a fresh app launch.

Open **Slideshow artwork** in the web editor to upload or delete up to 20 JPEG, PNG, or WebP images, each at most 8 MB and 8192 pixels wide or tall. Changes take effect without Apply to TV. HAL stores them in its private `files/slideshow-artwork` directory. In **HAL settings → Background**, turn on **Artwork slideshow** and **Use local artwork** to display the uploads. Turn off **Use local artwork** to select media artwork and configure your country and subscriptions. If the selected source has no usable image, the background stays black and the dashboard remains visible.

The rotation is shuffled, with the last shown image deferred when another can load. Captions show title and service, plus the original release year when available. Local captions use the sanitized filename without its extension. Daily media refresh depends on Android scheduling and network availability. A service selection filters the catalog; it does not verify your account or guarantee a title can play.

## Import and export settings

In the browser editor, open **Import / export settings**.

- **Export** using **Choose settings to export**. Check the settings you want, then select **Download settings file** to save `hal-settings.json`. The file reflects saved TV settings. Apply any dashboard edits you want included before downloading.
- **Import** using **Import file**. Open a HAL version 1 settings JSON file, review the choices, then select **Import selected settings to TV** and confirm. Imported dashboard layouts replace Home. Older files containing only Assistant become Home; files containing both append Assistant views to the file’s Home layout before replacement. Home’s existing friendly-name overrides take priority when the old surfaces name the same entity differently. Camera and entity selections merge with the TV's existing choices, and selected logins replace the saved logins. A confirmed import reloads the Home draft from the TV and discards unapplied edits.

To copy settings to another HAL TV, open that TV's browser editor and import the downloaded file. Camera and login choices start unchecked because they can include credentials. Uploaded slideshow images, appearance, and artwork-source settings are outside the file's scope.

## Use the remote

Move between ordinary cards with the D-pad and press OK to enter their controls. Stateful rows use icons to show their state, and OK runs a supported primary action such as toggling a light or locking or unlocking a lock. Select rows open a choice menu. Media-only cards show player panels. Increase card height in the editor to grow the artwork, with width reserved for labels. Taller panels also show more metadata and playback progress.

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

HAL appears in your TV's apps. To make the Home button open it, go to the cog → HAL settings, choose how it should take over, and turn on **HAL Helper** in your TV's Accessibility settings. The helper is optional, and if HAL fails to start a few times in a row, it switches itself off.

To update HAL, install a newer APK with the same signing key. Your settings stay in place. Builds that used the previous Android package ID install separately from HAL and require a fresh setup.

## Build from source

Open this folder in Android Studio, or use the Gradle wrapper with the required Java 25 toolchain and Android SDK (compile SDK 37). To build a debug APK:

```bash
./gradlew assembleDebug
```

The APK is in `app/build/outputs/apk/debug/`. A build you sign yourself can't update one from the releases page, or the other way round: Android only accepts updates signed with the same key, so you'd need to uninstall first and set up HAL again.

The release build looks for its signing key outside this repo, and comes out unsigned if it isn't there. When a release key exists, the current signing configuration retrieves its password through macOS Keychain.

For contributor setup, the existing Windows toolchain, test commands, source map, verification limits, and cross-chat documentation workflow, read [the development handoff](docs/DEVELOPMENT.md). [Behavior and decisions](docs/DECISIONS.md) explains the reasons behind the dashboard, remote, editor, visibility, artwork, and persistence behavior. Agents should build an APK only when explicitly asked.

Start each new HAL chat by reading the project instructions and handoff. Before committing or merging changes into `main`, review the affected documentation and include any needed updates with the changes. Follow the [development handoff](docs/DEVELOPMENT.md#keeping-work-understandable-across-chats) for the full workflow.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

Copyright 2026 Evo Factory S.à r.l.
