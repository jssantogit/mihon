# Fast Reading Discovery — incremental plan
Date: 2026-09-25
Branch: `tsuzuki/fast-reading-discovery` (fork of isolated generic compatibility branch)

## Gates and exact order
- **A / first commit:** deterministic tests for (1) fair allocation across pt-BR/pt/en when one language has many internal IDs and (2) language fallback when global/title preferences are empty, with local/download and explicit Add-on priority unchanged. Implement in `ResolveContentBinding.orderSources` and `RankContentOptions`. Confirm affected Domain/App/Format CI on the new SHA.
- **B / domain red→green:** introduce `DiscoverReadableChapter` (or a comparably named, DI-visible orchestrator) that first reuses `ResolveChapterContent.lookupOptions`, then drives `ResolveContentBinding.searchProgress` against only enabled eligible Add-ons, with source-budget and time-budget injection for deterministic virtual-time tests. Return typed progress (existing option, searching, candidate requiring confirmation, partial failure, first verified option, exhausted). Never persist a candidate without current confidence gates. Explicit languages keep authority.
- **C / targeted refresh:** make a successful BOUND refresh only the new source/binding instead of `RefreshChapterEvidence.execute` over all existing Add-ons; share in-flight refresh and option lookup. Reconciliation is additive; a failed refresh never discards old chapter evidence. Regression for very large old MangaDex bindings.
- **D / UI:** no blocking detail initial render; start automatic discovery once when selected chapter's options are empty; show source/language progress and first verified option; permit cancel/choose Add-on/search more; prioritize selected chapter over speculative first-unread warm-up. Keep Reader Activity and page-position contract.
- **E / regression/measurement:** input fake 0/slow/hung/ambiguous/healthy providers, disabled IDs, only pt-BR/en eligible, duplicate option cache, retry suppression, and chapter identity mismatch. Use CI and optional emulator before asking for a real APK; no live provider matrix.

## Commit and evidence rules
- Preserve original generic branch without editing it. All writes land only in this feature branch; compare/review before later integration.
- Gate each slice with tests and planner-selected CI; an unselected lane is not proof of passing. Treat Android fixture compile as different from emulator execution.
- No local Gradle, merge, PR, external live provider request or distribution APK without subsequent explicit authorization.
- No seven-minute user-visible wait: failures and time-budget exhaustion exit initial UX promptly, even if a third-party non-cooperative invocation needs separate containment.

## First slice acceptance
Run `RankContentOptionsTest` and `ResolveContentBindingTest` through Domain affected CI and verify Format. The initial batch with pt-BR IDs [14,15,11], pt [12], en [13] must query [14,12,13], not [14,15,11]. The selector with device locale pt-BR and no configured language preference must place pt-BR then pt then en before an unrelated newer-language option, without hiding other languages. Explicit preferences must not be silently overridden.