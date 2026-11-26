# Office SHIELD device validation — 2026-11-26

This is the earlier functional checkpoint. [Optimized validation](12_OPTIMIZED_VALIDATION.md) records the subsequent controlled AOT comparison, build-tool hardening, newer installed APK and final Home-helper restoration. Remaining-check statements below describe this report's original scope.

The user explicitly approved building/installing the patched APK and waking the office SHIELD for navigation, media and dream checks. Branch remains `codex/architecture-consolidation`, with uncommitted source/review work. No living-room device, saved integration configuration, launcher selection, screensaver selection or power timeout was changed.

## Build and installed identity

`:app:assembleDebug` passed using the established local JDK/SDK/Gradle and `ANDROID_USER_HOME=build/hal-toolchain/android-user`. `adb -s 10.1.0.130:5555 install -r` succeeded after verifying office `darcy`, SHIELD Android TV / Android 11 API 30. No uninstall or data clear occurred.

Installed HAL is 1.2.0/code 17, device-reported update time **2026-11-26 15:34:03**. Local and installed APK SHA-256 match: `957B573C142153996226E83E0995B19231524A41778C487F6C98EE58C1544839`. This build includes the Guava 33.7.2-android constraint described in [the dependency follow-up](10_VALIDATION_FOLLOWUP.md). APK signature v2 verifies with the established debug certificate; 16 KiB ZIP alignment checks pass. This is a debug installation, not an optimized release/profile benchmark.

## Functional results

| Check | Observed result / scope |
| --- | --- |
| Explicit editor activation and popup dismissal | The documented ENABLE_WEB_EDITOR action enabled the existing listener. Back dismissed the popup while the listener remained available. The first immediate screenshot was an unrendered grey frame; later captures showed the dashboard. That first capture is not evidence of steady-state blank rendering. |
| Saved dashboard and optional tabs | Calm rendered live weather, summaries, video artwork and Apps. Cameras and Frigate were unconfigured; their tabs were absent. No media-player reading was present in the saved view, so player metadata/transport/artwork could not be exercised. |
| D-pad and Home | ADB-injected Right moved app focus; Home selected the first tile. Up reached the ordinary summary. Left at the first app's dead edge retained focus. These exercise event handling, not physical remote timing/repeat fidelity. |
| Popup and forecast | OK on the weather-containing summary opened its mixed card. Current conditions, six hourly entries and five daily entries fit the popup; the included lock row and Unicode label rendered. No lock/light/media action was invoked. One Back closed the panel and restored summary focus. |
| Apps collapse | With saved Down mode, screenshot sequences after 32 seconds showed the Apps label; first Right expanded with first tile still focused, second Right moved focus to the next tile. Geometry remained in place. Some UIAutomator dumps returned null roots and stale files; those dumps are excluded from this result. |
| OK app launch / retained return | Navigated to the observed YouTube tile and pressed OK; Android focused YouTube. Home returned to HAL. App catalog query count stayed at one across repeated app/settings/dream returns; image load count stayed at eleven in the observed interval. |
| Editor suspension/rebind | After YouTube had been fully foreground for three seconds, editor requests failed to connect. On Home return, the listener rebound and configuration/layout revisions were preserved. Immediate transition requests can still succeed before the visibility debounce and are not evidence of failed suspension. Android's translucent settings panel leaves HAL partly visible and did not exercise full-hide teardown. |
| Android-selected dream | Opened the existing SHIELD screensaver settings component and chose Start now. Android reported HAL Artwork as the active dream, with test=false. Video and the clock/weather bubble rendered; later captures showed the bubble at a different position. The enabled editor stayed available with its revision preserved. |
| Dream wake / return | Injected Home dismissed the dream and HAL was foreground; later Home targeting remained functional. This covers a basic wake sequence, not held/repeated keys, all prior-app return cases or physical HDMI-CEC behavior. |
| Live editor reads | Eighteen concurrent state/catalog/config reads returned HTTP 200 (420 ms for this one batch). Cross-origin config GET returned 403. Log download returned HTTP 200 with the hal-logs.txt attachment. No private response bodies were retained as test fixtures. This is not an input-latency benchmark or synthetic-secret verification of both log sinks. |
| Saved state / process | Public section hashes and configuration/layout revisions matched the baseline after the exercised returns/reads. No configuration POST/import/Apply/Restore was sent. Updated HAL PID 9974 remained live and its crash buffer had zero entries when checked. |

The saved video library has five videos, one enabled playlist, three dashboard-flagged and two screensaver-flagged entries, with a 3,600-second switch interval. Dashboard and dream video rendered under these settings. No deliberate stall/seek/codec-pressure fixture or forced playlist change was introduced, so later-item watchdog, decoder pressure and all handoff races remain open. The device status decoder capability summary is not a count of active decoder instances.

## Automatic idle activation

Automatic activation passed with the existing 300,000 ms screen-off timeout and enabled editor. At approximately 260 seconds since the last input, Android was Awake with no dream; at approximately 330 seconds, it was Dreaming with HAL Artwork active and test=false. The last-input timestamp was identical across observations. No timeout or keep-awake setting was changed. This bounds the observed activation between those samples; it is not a millisecond-accurate measurement of the scheduled threshold.

During the automatic dream, the editor remained available, the configuration revision and saved section hash matched the baseline, app catalog queries remained one, image loads eleven and HA queue depth zero (high-water four). The 5,400,000 ms sleep timeout and Android screensaver/default-Home selections were unchanged. HAL Artwork was left running after the check, with no additional input. This verifies one normal idle activation with current video/editor settings; long sleep/HDMI-CEC remains open.

## Evidence and remaining limits

Ignored evidence is under `build/validation-20261126/`: patched-apk-build.log, patched-signature.txt, device-baseline.json, retained-return.json, concurrent-reads.json, after-live-reads.json, automatic-dream-wait.json, automatic-dream-result.json and screenshot sequences. Screenshots and XML can contain private dashboard labels and remain outside tracked documentation. Failed/stale UIAutomator captures are not acceptance evidence. Only the successful observed checks above are closed.

Still open: cameras/Frigate and player controls absent from this fixture; package install/update/remove/permission changes; all forecast mode transitions/offline/provider edge cases; synthetic log secrets on device; second-video stall/random seek/codec pressure; cold dream/process death, held/repeated wake keys and sleep/HDMI-CEC; long-use/pressure and API 28/other hardware; matched optimized baseline/candidate performance/profile campaign; release shrinker/signing/dynamic-path checks and full native/build-tool/license/backup review. No speedup, zero-resource claim, burn-in prevention or complete device certification is established.
