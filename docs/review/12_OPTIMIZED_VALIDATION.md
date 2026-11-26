# Optimized validation and dependency hardening — 2026-11-26

## Outcome

Optimized baseline/candidate APKs were built and exercised on the office SHIELD with equal compilation controls. The result is mixed: handled-Home first-app-focus p50 was essentially unchanged, ordinary Activity cold-start p50/tail was slower in the candidate, retained-intent returns were close, and the candidate's visible-memory snapshots were lower. This does **not** establish an overall speed improvement or close the startup-tail investigation.

The distribution audit found seven additional build-classpath advisory matches. Targeted constraints resolved them without changing runtime artifact coordinates/hashes. Final source passes 160 JVM tests, debug lint with zero errors, debug assembly and optimized R8/resource-shrink assembly. A reusable runtime/build dependency inventory validates as CycloneDX 1.6; its repeat OSV scan has zero matches for the 225 resolved Maven modules at query time.

The final patched debug APK was restored to the office SHIELD. Configuration, Home layout, video settings, sleep/screen-off settings and screensaver selection matched the pre-campaign checks. Android disabled the existing Home helper during force-stop/package-replacement testing; its original enabled choice was restored through Android's settings UI and a real injected Home event returned to HAL with the helper running. No secure-setting write command was used.

## Build identity and protocol

- Device: explicit `10.1.0.130:5555`, `darcy`, SHIELD Android TV / Android 11 API 30. No living-room operation. Existing output mode: 3840 × 2160, approximately 59.94 Hz.
- Baseline: exported source at `a006420c4fc92c575d8844ae1253f9e2fb4d8912` in an ignored build fixture, with matching benchmark build type, R8/resource shrinking, existing debug signing and shell-profileable manifest. Equivalent one-shot app-focus and key-dispatch trace probes were added to this fixture. Historical baseline vital lint was excluded; candidate lint passed. No baseline runtime refactor was applied.
- Candidate: consolidation source on `codex/architecture-consolidation`, initially before the separate build-classpath hardening below. The first-focus follow-up used the hardened candidate. Both phases record their exact APK hashes. Runtime artifact hashes are unchanged by build-only hardening.
- Both optimized APKs are non-debuggable and signed with the existing local debug key. Signature/alignment checks pass. This exercises the optimized pipeline but does not verify the external production signing key or distribution update path.
- Compilation: `cmd package compile -m speed -f com.evofactory.hal`, verified as `status=speed`. SHIELD rejects shell profile clearing (`-c`), so profiles were preserved; no root or permission bypass was attempted. Full-AOT compilation avoids reliance on profile-training state but is not representative of every consumer installation's compilation mode.
- Primary order: baseline, candidate, candidate, baseline; ten process-cold launches and ten retained-intent returns per block. Each block had an unmeasured warm-up. Saved app data and OS page caches were retained. Thermal status was zero in recorded before/after samples.
- Cold entry: explicit Activity MAIN/HOME intent after force-stop. Retained entry: explicit Activity intent after bringing YouTube forward. These are Activity timing metrics, not physical Home-to-photon latency. Existing animations, integration settings and power choices were retained.
- Separate handled-Home order: baseline, candidate, candidate, baseline; five cold `com.evofactory.hal.FOCUS_HOME` starts per block. Each start has a first-real-app-focus trace marker. All four follow-up traces contain five markers with no reported overwrite/data-loss/error counters. Marker time establishes focus on a launchable tile, not completed artwork decode or panel presentation.

The fixture contains live HA weather and background video with shuffle/random behavior. Saved settings were constant, but live network responses and selected video/decode workload were not replayed deterministically. Sample sizes and collection overhead limit causal and tail conclusions. No fresh-install/cache-miss, reboot, process-pressure or compilation-profile A/B result is implied.

| Optimized artifact | SHA-256 |
| --- | --- |
| Adapted audit baseline | `CD268E532D33707B260B6A3FD74CB546B4C8021D1DCB5139C35B5038AFFE0762` |
| Primary comparison candidate | `13BB4DF99F6A7A6953E4D4BFE0A931E1C4241B186A22D5BD83C28D181FE5BED0` |
| Hardened-tooling candidate used for handled-Home follow-up | `9721426EFC6A9D13DF21FF281213D1BDCD7D816AFB5B8028E5943EFA460CFD88` |

## Measurements

Nearest-rank quantiles; milliseconds. Pilot/failed runs are excluded.

| Metric | Baseline | Candidate | Interpretation |
| --- | ---: | ---: | --- |
| Process-cold Activity TotalTime p50, n=20 each | 840 | 880 | Candidate +40 ms in this fixture |
| Process-cold Activity TotalTime p90 | 872 | 927 | Candidate tail was slower |
| Process-cold Activity TotalTime p95 | 876 | 995 | Candidate tail was slower; not a performance acceptance pass |
| Process-cold Activity TotalTime maximum | 886 | 1,337 | One candidate outlier; deterministic isolation remains necessary |
| Retained Activity intent WaitTime p50, n=20 each | 97 | 98 | Close; not physical Home latency |
| Retained Activity intent WaitTime p95 | 120 | 112 | Small difference in a limited sample |
| Handled-Home start → first real app focus p50, n=10 each | 1,258.3 | 1,253.3 | Essentially unchanged |
| Handled-Home start → first real app focus p90 | 1,404.4 | 1,551.0 | Wider candidate tail |
| Visible PSS, two snapshots each, MiB | 294.8–309.7 | 260.7–265.1 | Lower observed candidate residency; not a general RAM target or causal proof |
| Hidden PSS, two snapshots each, MiB | 134.9–135.5 | 135.3–135.4 | No demonstrated hidden-memory reduction |

PID-specific crash buffers were empty in all primary blocks. Short reset graphics samples contained only 2–29 frames, so their jank percentages are unsuitable for a comparative frame-pacing claim. Perfetto scheduling/rendering/Binder traces are retained for analysis; main-thread traces identify Compose traversal/drawing and PlayerView inflation as existing startup work, not a proved regression root cause. No speculative rendering, decoder, lease or cache-pressure rewrite was made.

The ordinary cold Activity trace initially lacked a first-app-focus marker because that entry intentionally uses header focus. The follow-up used HAL Helper's existing handled-Home action, which exercises the correct focus contract. Early 24 MiB traces also overwrote their earliest events; they are excluded from first-focus analysis. Later traces use 64 MiB buffers. Failed pilots exposed an offline build dependency, PowerShell native-switch binding, unsupported profile clearing and Perfetto config-file SELinux restrictions; the corrected runs use supported full-AOT compilation and stdin trace configuration. Failed/partial pilot data is not pooled into the tables.

## Build-tool advisories and inventory

The expanded pre-hardening inventory had seven matches in the build-plugin classpath. None was in the already-patched Android runtime graph. These matches identify affected library versions; build-time exploitability was not reproduced.

| Build-only module | Previous | Selected patched version | Advisory |
| --- | --- | --- | --- |
| Guava JRE | 33.4.0-jre | 33.7.2-jre | GHSA-xxph-c9ww-hj94 |
| Commons Lang | 3.16.0 | 3.18.0 | GHSA-j288-q9x7-2f5v |
| jose4j | 0.9.5 | 0.9.6 | GHSA-3677-xxcr-wjqv |
| JDOM | 2.0.6 | 2.0.6.1 | GHSA-2363-cqg2-863c |
| Bouncy Castle provider/PKIX | 1.80.2 | 1.85 | GHSA-wg6q-6289-32hp, GHSA-9pwp-9qqc-pr26, GHSA-qp49-qgx5-5m26 |

Root `buildscript` constraints affect the build classpath; the separate Android Guava constraint remains in `app/build.gradle.kts`. BC utility/provider/PKIX versions are aligned. No blanket AndroidX/Media3/AGP/Kotlin upgrade was made. Final inventory: 82 runtime artifacts / 117 runtime graph nodes, 109 build-tool artifacts / 114 build graph nodes, 225 unique Maven modules across scopes. Parent-POM resolution supplies license declarations for all 82 runtime artifacts; a missing direct declaration is not a missing license.

The generated CycloneDX document has component hashes, dependency edges, scope markers and inherited license declarations. The official CycloneDX CLI 0.33.1 validates it as schema 1.6. A Danish-locale timestamp formatting issue in the initial exporter was corrected with invariant formatting. Perfetto 59.1 and CycloneDX validator binaries were obtained from official release locations and their hashes matched publisher metadata. No private trace, configuration body or source was uploaded.

OSV results are service matches at query time, not a CVE-free guarantee. Shaded/embedded native code, the Gradle distribution, JDK/SDK executables, complete distributed notices and actual backup/restore behavior are outside this Maven inventory. In particular, declaring Conscrypt's Maven license does not establish complete provenance/notices for its embedded BoringSSL. These distribution checks remain open. Backup policy is unchanged pending the owner's requested choice.

References: [OSV query protocol](https://google.github.io/osv.dev/post-v1-querybatch/), [Guava publisher advisory](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94), [Commons Lang fix](https://github.com/apache/commons-lang/commit/b424803abdb2bec818e4fbcb251ce031c22aca53), [jose4j issue](https://bitbucket.org/b_c/jose4j/issues/220/vuln-zip-bomb-attack), [JDOM release](https://github.com/hunterhacker/jdom/releases/tag/JDOM-2.0.6.1), [Bouncy Castle release](https://github.com/bcgit/bc-java/releases/tag/r1rv85v2), [CycloneDX validator](https://github.com/CycloneDX/cyclonedx-cli/releases/tag/v0.33.1), [Perfetto analysis](https://perfetto.dev/docs/getting-started/command-line-analysis).

## Final installed state and evidence

Restored debug HAL 1.2.0/code 17, device update time **2026-11-26 18:03:14**, APK SHA-256 **5668A9C32329F1504DB2D4AF964017937086B31F642234769F7EE50AD86017D7**. Signature v2 verifies with the established debug certificate; 16 KiB ZIP alignment passes. It includes the Android runtime security correction, first-focus diagnostic marker and builds with the hardened tooling. The Home helper is enabled/running again and Home returns to HAL. Public configuration/Home-layout/video hashes and screen-off/sleep/screensaver choices match the pre-campaign record. Revision UUIDs intentionally change across process replacement; no browser Apply/Restore/import was sent.

Ignored evidence: `build/review-campaign-20261126/` contains APK identities, build logs, comparison.json, focus-results.json, focus trace/CSV/integrity files, restoration-verification.json and pre/post advisory inventories. Primary block summaries/traces are under the directories referenced by blocks.json in `build/performance/`. Reusable inventory output is under `build/dependency-review/`. Traces, screenshots, private labels and full logs remain outside tracked handoff files. No merge or remote publication was performed; consult Git for the current branch/commit state.

## Remaining acceptance

The measurement campaign is complete for the bounded scenarios above. Overall performance acceptance remains open: isolate dynamic video/network workload and the wider startup/focus tail, then measure sustained frame pacing, physical input-to-presentation, allocation/retention paths and pressure/long-use behavior. No measured speedup is claimed.

Still unexercised: cameras/Frigate/player controls absent from the saved fixture; package/provider permission changes and all forecast/offline modes; on-device synthetic log-secret checks; deliberate second-video stall, random seek and decoder pressure; independent cold dream, repeated/held wake sequences, sleep/HDMI-CEC; API 28/other hardware; production signing/profile generation; embedded native/notices and backup policy/recovery. These remain explicit gates rather than inferred passes from unit tests or this fixture.
