# HAL

HAL stands for **Home Assistant Launcher**, a nod to HAL 9000 from *2001: A Space Odyssey*. It puts your apps, cameras, alerts and Home Assistant on one screen on Android TV and Google TV.

Project repository: https://github.com/Mongjohn/HAL

## Features

- **Cameras:** RTSP cameras in H.264 or H.265, with an optional substream for the grid. It plays as many live as your TV can decode, and shows the rest as their last picture.
- **Home Assistant:** build dashboards for the Assistant tab and the Home panel with sensors, weather, scenes and scripts. Up to three scenes or scripts can also appear as buttons in the launcher top bar.
- **Frigate alerts:** the last seven days of alerts, with clips that play full screen.
- **Watch Next:** a down chevron below a dock tile means the app has shared Watch Next cards with Android TV. Press Down for its cards and OK to open the content.
- **App row:** move tiles, hide them, see app info or uninstall.
- **Home button helper:** optionally makes the Home button bring you to HAL.
- **TV to TV:** send your settings to another TV on your network.
- **Diagnostics:** a built-in ping tool, network status, and debug logs with logins and tokens stripped out.

Cameras, Home Assistant and Frigate are all optional. HAL shows your apps and any Watch Next cards they share with Android TV.

## Privacy

HAL has no account or analytics. Its dashboard editor uses a temporary HTTP server on the TV. The app connects to the addresses you enter, to other TVs on your network when you transfer settings, and to the artwork URLs supplied by apps that publish Watch Next cards. Your Home Assistant password isn't stored: the TV keeps a login you can revoke from your Home Assistant profile.

## Edit dashboards

On the TV, open the cog → **HAL settings** → **Edit dashboard in browser**. Scan the QR code or open the displayed HTTP address on port 8765 from a phone or computer on the same Wi-Fi or Ethernet network. Choose the Assistant tab or Home panel, arrange and resize cards, and select their entities. Open **Friendly name** next to an entity in a card to set the name HAL uses throughout that surface. Use a Home Assistant dashboard as a template to start with a copy you can edit independently. Select **Apply to TV** to save the current surface.

The editor is accessible on the local network without pairing while it is enabled. HAL keeps the TV awake during the session. Select **Stop editing** on the TV to close it. It also closes when HAL leaves the screen or after 15 minutes without browser activity.

## Requirements

An Android TV or Google TV on Android 9 or later. Tested on Sony BRAVIA TVs on Android 9 and Google TV 14. Please share your own experience.

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

4. Install the APK:

   ```bash
   adb install -r HAL.apk
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

Open this folder in Android Studio, or build a debug APK from the command line, with `JAVA_HOME` set to the JDK that comes with Android Studio:

```bash
./gradlew assembleDebug
```

The APK is in `app/build/outputs/apk/debug/`. A build you sign yourself can't update one from the releases page, or the other way round: Android only accepts updates signed with the same key, so you'd need to uninstall first and set up HAL again.

The release build looks for its signing key outside this repo, and comes out unsigned if it isn't there.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

Copyright 2026 Evo Factory S.à r.l.
