# Tsuzuki — Fast Reading Discovery (design delta)
Date: 2026-09-25
Base: `tsuzuki/generic-addon-compatibility` @ `307612cfdaf14dce08c4f871f877aafa265897bc`
Status: scoped implementation branch; this document supersedes only the **no-readable-chapter** discovery trigger in the existing generic-add-on design. Explicit Change Source remains supported.

## Product contract
Open a title immediately from local metadata and cached chapter/evidence snapshots. Display which chapters are inferred versus supported by actual evidence. A reader who opens a chapter should see any already verified option immediately, without waiting for metadata refresh. If none exists, the app searches a **bounded** subset of installed, trusted, enabled Mihon CatalogueSources automatically; the reader can cancel, select another Add-on, or explicitly expand the search. Never promise readability before a real chapter option exists. Never install/trust/enable an extension implicitly.

The title-detail screen may *opportunistically* warm the first unread chapter only when local options are absent, but must never hold initial rendering or user navigation on that work. Chapter selection always supersedes warm-up. Repeated requests for the same title/chapter reuse one in-flight operation.

## Fast path and bounded cold path
1. Local-first: render cached title, inferred/observed chapters and last-used source; resolve cached options for the selected chapter. Prefer downloaded local content, then explicit title/Add-on preference, then configured languages, then locale tag/base and English only when no explicit language preference exists. Show alternatives rather than hiding other languages.
2. If an option is already available, expose it immediately. Metadata, other sources and neighboring chapters refresh separately and never gate Reader preparation.
3. If the chapter has no verified option, initiate a single visible, cancellable discovery session scoped to that canonical title/chapter. Reuse `ResolveContentBinding.searchProgress`, its strict match and confirmation gates, and installed-source eligibility. Choose at most **two** Add-ons in the first automatic attempt; use preferred/previously successful Add-ons when valid, then compatible smaller enabled packages. Allocate at most **six** internal-source queries across them, no more than three per Add-on and four concurrent. Distribute initial source slots fairly across preferred languages: do not let three pt-BR IDs starve English when locale defaults are pt-BR, pt, en. A user-defined language-only list is respected without injecting unrequested fallbacks.
4. On a safe `BOUND`, immediately fetch that binding's chapter inventory, reconcile only its evidence (without clearing existing evidence), invalidate affected option cache and retry the *current* selected chapter. Surface the first verified option without waiting for other providers. `CONFIRMATION_REQUIRED` only surfaces candidates to the user; no implicit identity linkage, no duplicate canonical title creation, no preference/progress write on search.
5. The **initial visible discovery budget is 10 seconds**; do not block the UI afterward. Results that finish legitimately may still be surfaced if the screen/session remains active and the user has not cancelled. Blocking third-party Java/HTTP can outlive coroutine cancellation, so bound executor/permit occupancy and isolate long-lived work; do not promise a hard kill. Explicit `Search more` continues with unqueried eligible IDs, never all installed extensions by default.
6. Partial failures remain partial. Treat HTTP/CAPTCHA/429/timeout as classified provider failures, never as definitive chapter absence. Keep results from healthy peers. Do not automatically re-run a failed provider in a tight loop.

## Caching and coalescing
Use existing content-option cache and in-flight content resolution first. Add short-lived, source-specific negative-cache timestamps only for actual successful empty searches/inventories; failures use capped retry/backoff, not negative availability. Binding and source eligibility changes invalidate only affected keys; installed/uninstalled updates invalidate affected decisions. Persist confirmed/matched bindings and observed inventories through existing repositories; avoid a new authoritative identity table. Cache TTL numbers must be empirically tuned after Android smoke rather than guessed from one provider's history. Refresh deduplication key: canonical title, selected chapter, source ID and version of active preferences where relevant.

## Performance measurement (targets, not guarantees)
- Cached title usable: p95 < 300 ms on reference test device; record first-render latency separately from background refresh.
- Cached readable option surfaced: p95 < 1 s, not including page download.
- Warm known-source first page: target 2–5 s; initial cold discovery result: target 5–10 s on responsive providers, with visible progress and a 10 s initial budget.
- Measure time-to-first-usable-option, time-to-first-rendered-page, provider query count, distinct internal IDs queried, concurrent calls, cache hit %, and duplicate inventory requests. External provider latency and noncooperative calls make these targets conditional, not guarantees.

## Invariants / exclusions
- Source IDs and URLs are operational; canonical title/chapter IDs are Tsuzuki-owned. Do not merge titles by text similarity or infer an edition from chapter count alone.
- Disabled internal sources must not be searched, probed, shown or opened even if old bindings/options exist.
- A source candidate is not a readable chapter. Reader preparation and successful page retrieval precede any preference promotion under the existing contract.
- Cold discovery must not run on every recomposition, scroll, page turn or library browse; one session per explicit selected chapter/eligible empty state, deduplicated.
- No extension-specific code path, CAPTCHA bypass, mass provider live queries, PR, merge or distribution APK in this feature branch.

## Incremental validation
A. Make existing source search batch language-fair and selector ranking locale-aware, with deterministic pure tests and no new live queries.
B. Add an injectable orchestration use case and deterministic fakes: empty→bounded two Add-ons→safe binding→single-source inventory/reconciliation→first option; healthy peer despite timeout; ambiguous candidate requires confirmation; no extra search when cached option exists; user cancellation.
C. Integrate with title detail + Reader selector, including first-option incremental UI, cancellation, explicit search-more and stable Reader instance/position. Implement targeted inventory refresh and in-flight coalescing before wiring a production auto-discovery trigger.
D. CI v2.1 affected lanes, full CI for cross-module integration, opt-in offline Android instrumentation (cold/warm, empty/slow/ambiguous), then pin an APK test SHA for a single approved real Death Note/MangaDot smoke. Check One Punch-Man regressions separately.

The existing `generic-addon-compatibility-design.md` remains authoritative for explicit Change Source behavior and identity safety; this delta authorizes only a bounded automatic search on a user-relevant empty reading state.