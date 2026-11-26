# Persistent review checklist and evidence ledger

Date: 2026-11-25. Baseline `a006420c4fc92c575d8844ae1253f9e2fb4d8912`. Branch `codex/architecture-performance-audit`.

The user subsequently approved implementation on a new `codex/architecture-consolidation` branch. This ledger remains the original review evidence. Consult [implementation status](IMPLEMENTATION_STATUS.md) for current source changes and verification; statements below about no implementation describe the review checkpoint.

The [2026-11-26 validation follow-up](10_VALIDATION_FOLLOWUP.md) adds read-only sleeping-device and debug-APK packaging evidence, a resolved-runtime component/advisory inventory and a targeted Guava source fix. The updated source passed tests/lint/benchmark compilation; the installed APK remains the previous build. Hardware and optimized-build gates remain open.

Subsequently, the user approved installing the patched debug APK and waking the office SHIELD. [Device validation](11_DEVICE_VALIDATION.md) records that installation and completed ADB-input navigation/popup/collapse/app-return, live forecast, editor and dream checks. This supersedes the preceding installation state; the broader fixture/physical/optimized-build gates remain open.

The subsequent [optimized comparison](12_OPTIMIZED_VALIDATION.md) supplies bounded AOT baseline/candidate startup/return/focus measurements and validated runtime/build dependency tooling. It records mixed results, targeted build-classpath hardening, the latest restored debug APK and Home-helper recovery. Broad unchecked items below are not closed by these narrower results.

This is a broad architecture/source assessment with identified runtime-validation gaps. All eight requested reports have been authored. It is **not** exhaustive execution-path, security or physical-device certification. A checkbox for static coverage does not claim every line or protocol/media combination was exercised.

## Completed analysis

- [x] Read local instructions, development/source-map/verification handoff, decisions and README; checked `CLAUDE.md` imports `@AGENTS.md`.
- [x] Inventoried source, assets/resources, tests, manifests, build dependencies and recent evolution; inspected generated debug manifest/transitive initializers and resolved dependency graph.
- [x] Traced process/Activity startup, resume/stop/new intents, optional Home helper and both DreamService lifecycles.
- [x] Mapped apps, tile artwork, Watch Next, provider access, app launching and saved selection.
- [x] Mapped configuration, migration, settings transfer, browser/API request flow, validation and secret handling.
- [x] Traced HA session/subscription/reconnect/forecast flow and media-art requests; reviewed visibility, layout and reusable state/action policy.
- [x] Reviewed grid/Calm/Apps/popup navigation ownership and current rendering/clock/motion mechanisms; identified runtime hypotheses separately.
- [x] Reviewed artwork catalog/scheduler/local files/slideshow/video ownership and hidden-host behavior.
- [x] Reviewed camera lifecycle, RTSP/filter/diagnostic responsibility, snapshot capture/persistence and decoder tracking; Frigate client/feed/thumbnail/clip ownership.
- [x] Reviewed network/diagnostics, icon font, theme/color-cycle and logging ownership.
- [x] Investigated semantic overlap and justified separation; documented current/target diagrams and a capability matrix.
- [x] Created canonical findings, dependency-aware roadmap, regression procedures and proposed guardrails without overwriting existing instructions.
- [x] Cross-checked current HAL documentation/rules against the review; recorded three stale DECISIONS statements and clarified lease/retained-state constraints in [09](09_DOCUMENTATION_ALIGNMENT.md). Existing instructions and handoff files remain unchanged.

## Verification actually run

| Check | Result / limitation |
| --- | --- |
| Starting Git tree | Clean; baseline `a006420`; new branch created before file edits |
| JVM tests + release dependency report | Offline success, 144 tests in 26 suites, 0 failures/errors/skips; no APK assembled |
| JS syntax | `node --check` passed editor.js, config.js, videos.js |
| Real-browser mock-API suite | `node tools/video-editor-check.cjs` passed video/config/touch/dashboard mouse/touch at 1920/1280/420 px after outside-sandbox retry; sandbox attempt timed out |
| Android lint | Incomplete: offline cache lacks `com.android.tools.lint:lint-gradle:32.5.0`; not a pass |
| ADB | Read-only office identity/API/fingerprint, meminfo/gfxinfo, filtered package flags/update time and current resumed Activity; no app launching, counter reset, install, reboot or settings change |
| Device measurements | One current memory snapshot + uncontrolled historical frame counters, debuggable installed app, active HAL dream; see 05 |
| Documentation checks | Link targets, expected report files, source-reference line anchors and whitespace diff checked before delivery |

Gradle/browser initially failed in the restricted sandbox; successful retries used the same local project tooling outside it. Local raw build logs are ignored under `build/audit-gradle.txt` and `build/audit-lint.txt`; do not promote them to portable evidence artifacts. ADB raw dumps were not committed. Automatic review did not reject an action.

## Remaining work before calling runtime validation complete

- [x] Initial optimized full-AOT comparison: 20 process-cold and 20 retained-intent samples per build, ten handled-Home first-app-focus samples per build; exact APK identities and mixed results recorded.
- [ ] Deterministic startup-tail isolation, consumer compilation/profile modes, reboot/first-install/cache-miss/offline/pressure and physical input-to-presentation acceptance.
- [ ] Perfetto scheduling/IO/Binder/render traces, remote key-to-presented-frame latency and rapid/held/reversed navigation journeys.
- [ ] Managed/native/graphics allocation and retention profiling; suspected Activity retention must be proved by paths, not object counts.
- [ ] Matched hidden residency CPU/network/decoder sampling and long-use/surface-switch/pressure tests.
- [ ] Camera snapshot IO/order/prune fault injection; invalid persisted camera repair; unsupported/malformed integration responses.
- [ ] Hardware video second-item stall/watchdog, decode load, app↔dream lease races, audio policy, cold dream, wake/repeats and sleep/HDMI-CEC.
- [ ] Package install/update/remove/enable/disable, cached artwork invalidation and missed hidden provider changes on device.
- [ ] Live HA/Frigate protocol/security matrix, forecast modes/fallbacks, unknown states and large/bursty feeds. No live integration credentials or dashboards were read for this audit.
- [x] Completed debug lint after obtaining the missing tool dependency: zero errors; see [implementation status](IMPLEMENTATION_STATUS.md).
- [ ] Release R8/resource/native packaging and release signing/update checks; the successful debug build/install does not establish these.
- [x] Initial resolved runtime artifact/hash inventory and OSV scan (82 matching debug/release artifacts); targeted Guava correction and zero matches on repeat scan. See the follow-up for scope and evidence.
- [x] Runtime/build Maven graph, artifact hashes, inherited POM license declarations and schema-valid CycloneDX 1.6 inventory; targeted fixes and zero OSV matches for 225 modules at query time.
- [ ] Embedded/shaded/native provenance, Gradle/JDK/SDK executable inventory, actual distributed notices and complete distribution SBOM; no CVE-free claim made.
- [ ] Backup eligibility/actual OEM backup behavior and product decision for secrets/reproducible files.
- [ ] API 28 and newer TV platforms, actual output resolutions/rates, panel-specific verification if requested.

## Continuation instructions

Keep findings stable by ID and update their evidence class only with recorded new evidence. Preserve audit-baseline line references as historical evidence and use [implementation status](IMPLEMENTATION_STATUS.md) for the approved consolidation and debug installation. If new inspection contradicts a finding, correct the canonical entry and summaries. Source consolidation is implemented; measured performance improvement and the remaining runtime checks are not established.
