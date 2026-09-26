# Fast Reading Discovery — verification checkpoint
Date: 2026-09-26
Branch: `tsuzuki/fast-reading-discovery`
Base branch: isolated `tsuzuki/generic-addon-compatibility`; no merge, PR, live extension matrix or APK in this work.

## Human baseline (pre-auto-discovery APK)
Death Note had no MangaDot binding. Explicit `Link alternative reading Add-on → MangaDot` found and persisted matching editions; the English internal source returned 544 chapter entries, the chapter-specific provider and selector each accepted 25 reading options, and the actual English chapter 1 loaded in the Reader. The supplied screenshot shows its first page and a 1/47 page indicator. That verifies one real chapter's first page, **not** every page or all 544 entries. Existing MangaDex English inventory was empty for that bound edition; absence is source-specific.

## Implemented code slices
- Language-fair initial batches in `ResolveContentBinding`, locale fallback in `RankContentOptions`.
- Two-Add-on/four-internal-source initial planner. Only trusted, installed, enabled internal catalogue sources from current eligibility are considered. Explicit language preferences remain authoritative.
- `DiscoverReadableChapter`: check verified existing options first (2-second cooperative lookup budget), then a 10-second bounded discovery session. Candidate ambiguity never creates a binding or chapter option. Unhealthy peers do not suppress healthy results. Per-binding inventory and chapter lookup are targeted, not all 42 sibling IDs.
- Two concurrent targeted inventory/lookup permits, with duplicate binding-ID suppression. A slow first binding must not block a healthy second binding's first usable result.
- Reader and detail selector integration: show progress, source errors and confirmation/manual fallback. Display the first actually verified chapter option immediately. Dismissing the selector, choosing an explicit Add-on or selecting an option cancels the visible automatic discovery without clearing Reader state or writing an unverified preference.
- A separate Main-scope 10-second visible deadline ends the spinner and exposes manual options even if an IO provider never cooperates with coroutine cancellation. Late provider events are rejected after cancellation.
- Explicit Link Add-on still has its legacy title-wide post-binding refresh; converting it to incremental binding-specific updates is a later optimization and **must not be confused** with the new targeted automatic path.

## Engineering boundaries
- 10 seconds is the **visible waiting limit**, enforced separately from the cooperative domain timeout. It is NOT a hard execution timeout for non-interruptible third-party Java code; blocked provider workers can continue after the UI exits. Executor isolation and pressure tests are still required before claiming no resource starvation.
- The initial automatic pass selects only up to two Add-ons. It may miss MangaDot if the user's installed inventory ranks other Add-ons first; no universal coverage guarantee. Explicit Add-on search remains available; adaptive later waves require separate measured design.
- No unverified candidate, inferred chapter, search hit or installed Add-on is presented as a readable chapter.
- Canonical title/chapter identity, explicit confidence gates and Reader preference confirmation remain unchanged.

## Deterministic verification
Domain tests: cached option bypass, language-fair 3-slot allocation, disabled/unrelated IDs excluded, two-Add-on/four-source cap, ambiguity needing confirmation, healthy peer after failing peer, cooperative timeout, duplicate binding suppression and concurrent fast/slow targeted refresh.
App tests: selector publishes a verified option before discovery completes, timeout leads to a manual fallback, cancellation preserves the selected chapter, ambiguous results remain confirmation-only, and existing post-binding selector tests continue to run.
Separate targeted provider tests cover bound-edition-only inventory, source-disabled refusal and cache invalidation scoped to the affected Add-on.

## Remaining acceptance — real Android, SHA-pinned
1. Cold Death Note without a MangaDot binding: record which Add-ons/internal IDs the initial pass selected, event times, first verified option time and explicit fallback when MangaDot is not in the first batch.
2. Warm Death Note with an existing valid MangaDot EN binding: time opening detail, selector-ready, Reader first rendered page, second launch, and page-position restoration after source selection.
3. Slow empty/HTTP failure source alongside healthy EN on an emulator. Healthy options must become selectable before timeout. Confirm no permanently blocked UI even if a non-cooperative extension call continues elsewhere.
4. Separate One Punch-Man smoke in pt-BR: 169 observed MangaDex inventory rows versus previously displayed 163 provisional entries; verify identity, ordering and real page availability without treating inferred chapters as readable.
5. Measure p50/p95 first cached render, first verified option and first rendered page on a specified reference Android device. Targets are 300ms cached detail, 1s cached option and 5–10s responsive cold discovery; none is a verified benchmark yet.

No merge/PR or distribution APK until review and authorization.
