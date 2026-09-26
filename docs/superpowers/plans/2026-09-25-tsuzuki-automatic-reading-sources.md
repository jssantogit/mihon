# Automatic reading sources — implementation plan
Date: 2026-09-25
Branch: `tsuzuki/automatic-reading-sources`

A. Diagnostic-informed planner: tests for Kimetsu's third installed pt-BR Add-on, giant MangaDot multilingual EN coverage, disabled IDs, eight-package/sixteen-source budget; implement `PlanFastReadingDiscovery.planAutomatic` without changing manual search semantics. First two prioritized targets retain initial ordering.
B. Progressive domain: `DiscoverReadableChapter` executes the entire eligible first-wave queue, bounded to three concurrent Add-ons / two targeted refreshes, with per-ID dedup. Publish a new Ready event for every *new* verified chapter option, not only the first; preserve typed confirmation, timeout and partial failure. Add deterministic two-provider incremental and failed-first-two/healthy-third tests.
C. Normal selector: merge multiple Ready events, mark ongoing enrichment unobtrusively, finish without replacing valid options when a later provider fails or times out. Remove manual search/Add-on choice buttons from normal chapter selection, preserve only diagnostics tools and required identity confirmation. Keep Retry when actually unavailable.
D. Cold title load: prevent an all-language chapter refresh on every title open; explicit Refresh stays comprehensive. After targeted discovery, detail reloads local reconciled evidence once. Regression that initial open does not invoke all-Add-on probe while explicit refresh does.
E. Verify affected Domain/App/Format first, full CI after integration. Do not produce an APK or merge until requested. A later device smoke must test virgin Kimetsu and Death Note, actual source selection and first page, two alternatives and zero user-driven Add-on navigation.

Follow-up after first green: larger-install continuation queue with persistent search state and per-title successful-source ranking; prepare background work while browsing rather than waiting for user to choose a chapter; measured behavior under noncooperative third-party Java providers; edition-confirmation UX; debug-only diagnostics visibility.
