# HAL architecture and performance review

Review date: 2026-11-25. Original audit baseline: `a006420c4fc92c575d8844ae1253f9e2fb4d8912`, reviewed on `codex/architecture-performance-audit`. The original audit changed documentation only; findings below describe that baseline rather than the subsequent implementation.

Subsequent direction: the user approved implementing the roadmap on `codex/architecture-consolidation`. This report preserves the original audit baseline; [implementation status](IMPLEMENTATION_STATUS.md) records what is now implemented and which device/release gates remain open.

## Assessment for the owner

HAL has a viable native foundation and does not warrant a rewrite. Its biggest architectural weakness is uneven ownership: some expensive resources are already carefully shared, while other data is repeatedly fetched, decoded or reconstructed by individual screens. The appropriate next step is a series of small consolidations with measurements and behavior checks between them.

There is meaningful accumulated technical debt, especially in the Activity's configuration orchestration, broad settings invalidation, per-widget image loading and camera snapshot persistence. However, the repository does **not** contain evidence for the claim that every feature has built its own complete parallel infrastructure. Recent work removed the internal idle screensaver and reused the dashboard's media renderer in the global screensaver. Those changes should be preserved.

Two high-priority reliability findings deserve attention before performance refactoring: a failed camera snapshot write can escape its worker thread and terminate HAL (HAL-01), and malformed saved camera JSON can prevent startup (HAL-02). These are confirmed failure paths; this audit did not reproduce a crash on the TV. No P0 issue was established.

## Highest-value improvements

| Order | Proposal | Benefit the owner may notice | Main trade-off |
| --- | --- | --- | --- |
| 1 | Contain snapshot storage failures and serialize writes (HAL-01) | HAL keeps working when storage fails; fewer competing writes | Preserve the newest picture and handle late saves during camera removal |
| 2 | Recover safely from invalid camera settings (HAL-02) | Broken optional camera data cannot lock out the launcher | Preserve damaged configuration for repair rather than silently overwriting it |
| 3 | Establish repeatable startup, Home-return and input measurements (HAL-12) | Makes subsequent speed claims trustworthy | Requires dedicated TV test sessions and a release-equivalent build |
| 4 | Own app discovery, image invalidation and Watch Next refresh in a small catalog capability (HAL-03) | Less repeated work after returning Home; updated app artwork | Package changes while HAL is hidden still require reconciliation |
| 5 | Share bounded media image results and in-flight requests (HAL-04) | Less refetching and image reappearance during surface changes | Cache keys must isolate HA accounts and image sizes |
| 6 | Observe only relevant preference sections (HAL-05) | Avoid unrelated work whenever a slideshow updates its remembered image | Migrations and legacy keys must keep working |
| 7 | Subscribe only to weather forecasts actually needed (HAL-06) | Lower network/processing overhead | Current weather and independent surface choices must remain correct |
| 8 | Move large editor transformations away from the input thread (HAL-07) | Remote navigation remains responsive during large saves/exports | Revision checks must still surround the actual commit |
| 9 | Bound thumbnail decoding and cache bytes (HAL-09), and fix video item readiness tracking (HAL-10) | More predictable memory and recovery from bad videos | Keep images sharp and avoid flashes between valid videos |

The ordering reflects failure containment, measurement, then elimination of repeated work. Performance gains remain unquantified until the controlled tests in [05](05_PERFORMANCE_AUDIT.md) are run. Separating configuration ownership also benefits future development even if its immediate visible speedup is small.

## What already works architecturally

- Native Kotlin/Compose rendering and Media3 playback suit the platform. No website is embedded as HAL's TV interface.
- The dashboard and global artwork screensaver share decoded slideshow state, a leased background video player and matching HA connections. They do not need another universal runtime manager.
- The HA primary-action and state-color logic is reusable, capability-aware and tested. Visibility references are included in subscriptions, and reconnect snapshots remove stale entity membership.
- Recurring UI producers generally stop with visibility while keeping dashboard focus and useful cached values. Android owns global screensaver activation and wake.
- The editor has revision confirmation, explicit UTF-8 handling, local-subnet/Host/Origin checks, redacted ordinary responses and confined image uploads. Its unpaired HTTP access is an intentional local-network product choice.
- Persisted schema migrations and the browser regression suite protect many features that could otherwise be lost in a refactor.

Preserve themes, visual quality, all card types, grid geometry, Calm popup behavior, Apps collapse, Home targeting, camera diagnostics, separate camera screensaver, settings transfer and optional integration isolation.

## Measurements and limits

Read-only ADB inspection confirmed the office SHIELD at `10.1.0.130:5555`, product `darcy`, Android 11/API 30. The installed HAL was **debuggable** version 1.2.0/code 17, updated 2026-11-24 at device-reported 23:17:36; its exact source revision was not established. HAL Artwork's DreamActivity was foreground and MainActivity was hidden.

The existing graphics counters reported 269,400 frames and 666 janky frames (0.25%), CPU-frame percentiles 5/6/7/12 ms at p50/p90/p95/p99, and approximately 346 MiB total proportional memory. These are uncontrolled historical counters and one memory snapshot, **not** a release baseline or proof of input responsiveness. They do not identify a leak, certify 4K performance, or support a rewrite. Details and reproducibility limits are in [05](05_PERFORMANCE_AUDIT.md).

All 144 JVM tests passed, with zero failures/errors/skips across 26 suites. JavaScript syntax checks passed. The real-browser mock-API checks passed at 1920, 1280 and 420 px after rerunning outside sandbox restrictions. These tests do not exercise Android lifecycle races, hardware decoders or physical remote input.

Controlled cold/warm/hot startup, remote latency, Perfetto, release memory/allocation profiling, standby/CEC, long-duration residency, dependency vulnerability scanning and hardware failure injection remain open. [The persistent checklist](REVIEW_STATUS.md) distinguishes completed analysis from remaining validation; this report must not be described as exhaustive runtime certification.

## Reading the reports

1. [Current architecture and capability matrix](02_CURRENT_ARCHITECTURE.md)
2. [Duplication and consolidation](03_DUPLICATION_AND_CONSOLIDATION.md)
3. [Proposed target architecture](04_TARGET_ARCHITECTURE.md)
4. [Performance audit and measurement procedures](05_PERFORMANCE_AUDIT.md)
5. [Canonical technical findings](06_CODE_REVIEW.md)
6. [Dependency-aware implementation roadmap](07_IMPLEMENTATION_ROADMAP.md)
7. [Proposed development guardrails](08_DEVELOPMENT_GUARDRAILS.md)
8. [Alignment with HAL documentation and rules](09_DOCUMENTATION_ALIGNMENT.md)

Approve an implementation phase only after reviewing its scope and acceptance criteria in the roadmap. No implementation, APK installation, TV settings change or launcher replacement was performed here.
