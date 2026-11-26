# Alignment with HAL documentation and rules

Reconciled 2026-11-26 against local `AGENTS.md`, `CLAUDE.md`, README, DEVELOPMENT, DECISIONS, the review and source on `codex/architecture-consolidation`. The original audit describes baseline `a006420`; [implementation status](IMPLEMENTATION_STATUS.md) records subsequent source changes, verification and open gates. Consult Git for current commit/worktree state. [Optimized validation](12_OPTIMIZED_VALIDATION.md) is the newest measured/install checkpoint, including mixed performance results and Home-helper restoration.

The [dependency follow-up](10_VALIDATION_FOLLOWUP.md) found and corrected a transitive Guava advisory and reran source checks. The user subsequently approved its debug build/install and wake checks; [device validation](11_DEVICE_VALIDATION.md) records the installed correction and scoped live results. The remaining local forecast instruction now matches demand-driven subscriptions. Earlier records below retain their original scope.

## Requirement and implementation cross-check

| HAL contract | Current alignment |
| --- | --- |
| Home-only layouts, Assistant/dock migration, version-1 imports, positions and visibility | Compatibility readers and existing layout/focus behavior remain. Consolidation does not authorize changing saved geometry or removing migration paths. |
| Web owns configuration; independent drafts, UTF-8, redacted reads and revision checks | Editor transformations use bounded workers and prepared Apply/Restore encoding. Main commits recheck revision/listener ownership. Final combined import mutation remains on main to preserve ordering; further optimization is open. Persistence remains asynchronous. |
| Android owns dreaming, wake and sleep; independent cold dream and separate HAL Cameras | Preserved. No foreground idle overlay, keep-awake hooks, system-setting writes or forced wake launch were introduced. |
| Shared HA/video 750 ms leases and retained dashboard/focus/artwork/prepared clip | Preserved. Consolidation does not change handoff durations or authorize ordinary-hide eviction. Measurements alone cannot authorize these product changes. |
| Optional storage/configuration failures must not crash the launcher | Snapshot persistence is bounded and failure-contained. Malformed saved camera JSON loads safely and retains repair data. Unit coverage passes; device fault/parity checks remain open. |
| No hidden refresh service; visible demand owns expensive producers | ApplicationCatalog records package invalidation while hidden; queries require a visible launcher. Watch Next observation follows visible media-art demand. Artwork visible-host and cold-dream/editor rules remain. |
| Separate forecast modes and live current weather | Active owners contribute demand on the existing HA session. All Off requests no forecasts; dashboard/popup hourly retains daily demand for the current-day summary. Current-weather discovery and visibility-rule subscriptions remain. |
| Bounded image work with separate trust domains | App, HA and Frigate caches coalesce requests with byte/concurrency limits. HA keys separate origin/account/content/size; Frigate retains independent authentication/pinning. Initial budgets are not device-tuned and exclude retained UI/native memory. |
| Media progress, protected artwork/state colors, remote focus and collapse sequences | Existing behavior retained. No speculative rendering/decoder rewrite or burn-in-prevention claim. Physical remote, playback and dream checks remain open. |
| Explicit resource ownership, cancellation, bounds and invalidation | Accepted guardrails are in local instructions and portable handoff. Superseded app/provider/image producers were removed; legacy readers remain. HA queue depth is instrumented, but the queue remains unlimited pending a correctness-preserving policy supported by evidence. |
| Explicit office target, matching signing key and build only when requested | After initially choosing source-only, the user requested the debug build/install. In-place install on `10.1.0.130:5555` succeeded with the existing key, without clearing data or changing system choices. The separate controlled benchmark campaign remains pending. |

## Documentation drift resolved

- DECISIONS identifies the web UI as HAL configuration owner, Android as global screensaver owner, and artwork jobs as permitted while either HAL or HAL Artwork is visible.
- The later weather paragraph describes demand-driven forecasts rather than unconditional hourly/daily subscriptions.
- README explains app catalog refresh and recovering the matching signing key for updates rather than uninstalling HAL to bypass a mismatch.
- DEVELOPMENT contains the current source map, checks, installation and remaining work. AGENTS records accepted ownership/performance rules and the current handoff. CLAUDE still imports `@AGENTS.md`; both local instruction files remain Git-ignored.
- Review introductions distinguish the original audit from the accepted roadmap and subsequent implementation. Guardrails are adopted principles; conditional experiments remain conditional. Dated older verification remains historical evidence.

## Verification limits

Source verification passed 160 JVM tests in 29 suites, debug lint with zero errors (25 warnings, two hints), benchmark Kotlin/manifest compilation and browser checks at 1920/1280/420 px. The requested debug APK built and installed on the office SHIELD as HAL 1.2.0/code 17 on 2026-11-25; basic launch and process survival were checked.

These checks do not establish release-equivalent speed, physical navigation parity, live HA/Frigate/camera/video behavior, dream/wake/CEC or pressure behavior. Release packaging/profile checks, controlled measurements and the hardware matrix remain open in [implementation status](IMPLEMENTATION_STATUS.md). No performance gain or complete runtime certification is claimed.
