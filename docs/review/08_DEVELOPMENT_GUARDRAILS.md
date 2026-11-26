# Development guardrails

Implementation continuation: the user approved the roadmap on 2026-11-25. Scoped ownership/performance rules are now adopted in local `AGENTS.md`, with portable context in `docs/DEVELOPMENT.md` and `docs/DECISIONS.md`; see [implementation status](IMPLEMENTATION_STATUS.md). The text below preserves the original audit proposal and rationale. Product changes, benchmark claims and conditional experiments still require their stated evidence/approval gates.

These accepted principles supplement the existing product contract. Local `AGENTS.md` and the portable handoff contain the operative rules, including new-branch work, explicit office SHIELD targeting, no APK assembly without request, browser-only settings, saved-format compatibility and Android-owned global dreaming. The reference text below describes intended guardrails; it does not claim every inherited implementation already satisfies them. In particular, HA queue instrumentation is implemented, while a bounded correctness-preserving overload policy remains conditional on protocol evidence.

## Adopted principles and rationale

```markdown
## Capability ownership and performance

- Before implementing a feature, consult docs/review/02_CURRENT_ARCHITECTURE.md and search for existing implementations of every required capability. Name the current owner and consumers before adding another cache, listener, coroutine scope, transport, timer, state mirror or persistence path.
- Extend the authoritative implementation when responsibilities match. Introduce a separate mechanism only with documented differences in security, lifecycle, performance or behavior. Do not centralize all concerns in one global manager.
- Every process-owned resource needs explicit consumer/visibility ownership, cancellation/release policy and appropriate cache/queue/concurrency bounds and invalidation. Use application context where an Android Context is needed; pure state and algorithms need none. A composable that displays shared data should not independently refetch/decode equivalent content without justification.
- Preserve independent cold DreamService operation, existing HA/video handoff leases, Android-owned screensaver activation, warm dashboard focus and saved migration contracts. Do not restore HAL idle overlays, global keep-awake hooks or permanent background services for cache freshness.
- SharedPreferences raw data is cached by Android; distinguish repeated parsing from disk reads. Observe changed sections/keys and keep browser drafts independent from saved TV state. Do not add an all-settings observable that makes unrelated writes invalidate every screen.
- Keep optional integration/storage/image failures outside the app-launch critical path. Expected failures must not escape raw threads or lifecycle coroutines and terminate the launcher. Preserve last-good data where safe and retain invalid saved user data for repair.
- Keep slow Binder/disk/large parsing/encoding/decoding work off the input thread where safe. Snapshot immutable data and recheck revisions before committing results; do not access mutable UI state from arbitrary workers. Preserve cancellation rather than swallowing it.
- Keep protocol queues bounded with an explicit correctness-preserving overload policy. Do not conflate command replies or entity deltas. Avoid unbounded image decode parallelism, raw writer thread creation and count-only caches for variable-size images.
- Preserve remote key sequences, repeats/releases, collapse wake consumption, popup dismissal, hidden-item recovery and Home targeting. Share pure routing decisions where equivalent; keep each presentation's focus graph local. Do not add arbitrary delays to hide focus races.
- Read frequent animation/progress state in drawing/placement/layers when possible; cache expensive unchanged projections against all real inputs. Profile before changing eager layout, rendering effects or decoder policy. Preserve visual quality and protected artwork/colors.
- For architectural or performance changes, establish the affected behavioral fixture and matched device baseline first. Record build/commit/device/API/display/compilation/scenario, p50/tail latency and memory/request counts as applicable. Unit tests do not establish SHIELD speed or decoder correctness.
- Do not remove legacy readers until supported saved layouts/imports migrate correctly; remove replaced active runtime implementations after consumers migrate. Do not leave permanent duplicate refresh or dual-write paths.
- Before adding/upgrading a dependency, identify the missing capability, existing alternatives, Android/API/native impact and measured need. Do not replace optimized libraries with custom infrastructure merely to reduce dependency count.
- Keep secrets out of all logging sinks, trace names and committed artifacts. Preserve distinct HA, Frigate and public-artwork trust boundaries. Never share authenticated image results across account/security generations.
- Update the capability matrix, affected source map/decisions/README and verification limits with behavior changes. Review proposals remain proposals until accepted; preserve the reason for intentional separation.
```

## How to use these rules during development

For each change, answer in its design/PR description: which existing capabilities are used, whether ownership changes, what redundant execution is eliminated, the state authority, invalidation and hidden-host behavior, resource bounds, regression scenarios and measured versus inferred benefit. A small feature can answer in a paragraph; these rules are not a demand for a new architecture document on every edit.

Use the existing pure model tests for layout, visibility, action selection, media clocks, bubbles and settings. Add targeted tests for failures or ownership transitions rather than tests that merely duplicate implementation details. Introduce Android lifecycle/remote/media coverage for cases that JVM/browser checks cannot represent. Keep browser pointer-event tests for drag/resize and extension-free console diagnosis.

## Definition of done

- Current behavior and saved configuration survive; intended changes are explicit.
- Every new shared mechanism has an owner, lifecycle, bounds, error path and consumer list.
- Old consumers and active producers are removed or the remaining compatibility reason is documented.
- Relevant tests/checks pass; unavailable checks are reported with their exact reason.
- Significant performance claims have paired device evidence; expected benefits are labelled when unmeasured.
- Release/profile/signing/platform checks are completed when relevant and authorized.
- Documentation distinguishes implemented behavior, approved requirements, proposals and open device checks.

Keep these adopted principles synchronized with local instructions and the portable handoff; preserve project-specific product requirements and record inherited exceptions until resolved.
