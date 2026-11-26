# Validation follow-up — 2026-11-26

This report preserves the initial read-only/source checkpoint. The user subsequently approved the patched build/install and wake checks; [device validation](11_DEVICE_VALIDATION.md) is the newer installation/runtime record. Statements below that the source correction was not installed describe this earlier checkpoint.

Branch: `codex/architecture-consolidation`, uncommitted working tree based on `a006420`. This session completed read-only device checks and source/dependency checks. It did not assemble/install another APK, wake the office TV or change saved/system settings.

## Installed build and sleeping-device checks

- Explicit office serial `10.1.0.130:5555` identified `darcy`, SHIELD Android TV, Android 11/API 30. The living-room device was not used.
- Installed HAL remains 1.2.0/code 17, update time 2026-11-25 22:07:49. Reading the installed `base.apk` hash matched the local debug APK: `0C3EC9E740643609FB6DBE9B347FAFB31AEC2316FCEF9572B4CB5795769DB499`.
- HAL PID 21534 survived since the installation check. Its crash buffer had zero entries at inspection. This does not establish that no other process or earlier process ever crashed.
- Device was Asleep, display OFF; no active dream. HAL Artwork remained the selected Android screensaver, the system launcher remained the resolved default Home, and the Home helper service was present. No settings were written.
- The editor listener was unreachable while asleep; jobscheduler reported zero running HAL background jobs. These snapshots do not prove zero hidden CPU/network work or validate the complete visible/hidden lifecycle.
- Passive memory was 202,062 KiB PSS, 251,500 KiB RSS and 8,364 KiB swap PSS. Historical graphics counters were 314,427 frames / 1,756 janky (0.56%). Different workload/state and accumulated counters prohibit comparison with the old baseline as a speed or memory improvement.
- The configured display mode was 3840 × 2160 at approximately 59.94 Hz. No visible frame, OLED appearance, physical input latency, dream transition or HDMI-CEC behavior was tested while the display was off.

Ignored evidence: `build/performance/20261126-105647-Snapshot.json`.

## Existing debug APK packaging

`apksigner verify --verbose --print-certs` passed with APK signature scheme v2, compatible with the supported API range. The signer was the Android debug certificate, SHA-256 `e5a85d1d241f579d453bc904a75686e39e0b96680253b80e433f403248a0403c`. This is not a release-signing validation.

`zipalign -c -P 16 -v 4` passed. Eight native libraries were present: AndroidX graphics path and Conscrypt for arm64-v8a, armeabi-v7a, x86 and x86_64. Direct ELF inspection found all PT_LOAD segment alignments at 16,384 bytes. This checks packaging/alignment of the existing debug APK, not runtime compatibility on a 16 KiB-page device or optimized release output.

Ignored evidence: `build/validation-20261126/native-alignment.json`.

## Dependency advisory and source correction

The initial resolved runtime inventory contained 82 Maven artifacts with SHA-256 hashes. OSV querybatch matched `com.google.guava:guava:33.3.1-android`, pulled through `androidx.media3:media3-common:1.11.1`, to GHSA-xxph-c9ww-hj94 / CVE-2026-102554. The [Guava publisher advisory](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94) identifies uncontrolled allocation when Java-deserializing affected collection types and lists 33.7.2-android as patched.

No `ObjectInputStream`, `ObjectInput`, `ObjectOutputStream` or `readObject` call was found in HAL's main Kotlin source. This limits the identified direct attack surface; it is not whole-program proof that the installed dependency is unreachable.

Added a dependency constraint for Guava 33.7.2-android with the advisory rationale. Resolved changes are Guava 33.3.1-android → 33.7.2-android, failureaccess 1.0.2 → 1.0.3 and jspecify 1.0.0 → 1.0.1. Debug and release runtime inventories have identical 82 artifact coordinates/hashes after resolution. The repeat OSV batch returned zero matches and no pagination. That means no matches in that service at query time; it is not a CVE-free certification, native/BoringSSL provenance review or build-tool scan.

The source correction is **not installed**. The installed/local debug APK hash above remains unchanged. No further APK assembly was performed.

## Source verification and inventory limits

After the constraint change, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:compileBenchmarkKotlin` and `:app:processBenchmarkManifest` passed. Test XML totals 160 tests in 29 suites with no failures/errors/skips. Lint reports zero errors, 25 warnings and two hints.

Ignored evidence under `build/validation-20261126/`: `source-checks.log`, `dependencies-before.json`, `dependencies-debug.json`, `dependencies-release.json`, `guava-insight.txt`, `osv-before.json`, `osv-result.json` and `hal-runtime.cdx.json`. The CycloneDX-format component inventory records artifact hashes and direct POM license declarations. It omits dependency edges, build plugins and embedded native provenance and has not been schema-validated. Five coordinates lack direct POM license declarations in this extraction; parent inheritance and actual distributed notices still require review. Missing direct declarations do not establish missing licenses.

## Remaining checks

- Build/install the patched candidate when explicitly requested; retain the existing signing key and saved data. Optimized baseline/candidate APKs and controlled compilation/profile measurements remain separate work.
- With the office TV awake: physical remote focus/collapse/Home, retained return, enabled-editor suspension/rebind, package/provider events, camera/Frigate quality, live forecast demand, video second-item watchdog and Activity/dream handoff.
- Android wake/repeats/sleep/HDMI-CEC, cold dream, long-use and pressure tests; API 28 and other platform/device coverage.
- Release shrinker/signing/packaging and dynamic paths, complete license/native/build-tool inventory and backup recovery/product policy. Source manifest still has no backup exclusions; installed flags include ALLOW_BACKUP. Actual OEM backup/recovery remains untested and policy unchanged.

The separate controlled benchmark campaign has not run. No performance improvement is claimed.
