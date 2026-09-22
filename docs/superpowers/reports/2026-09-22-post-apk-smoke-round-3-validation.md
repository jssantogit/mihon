# Tsuzuki — post-APK Round 3: unified chapter rows

**Baseline:** `tsuzuki/bootstrap` @ `ae6ee77b383c1b59e4f9936f2bfe4c0d3ac9a4eb`  
**Working branch:** `tsuzuki/post-apk-smoke-round-3`  
**Specification:** `docs/superpowers/specs/2026-09-20-tsuzuki-modular-runtime-architecture-design.md` §4.3 (amended on this branch)  
**Implementation plan:** `docs/superpowers/plans/2026-09-22-tsuzuki-post-apk-smoke-round-3.md`

## Implemented

- One chapter list in title details: actual canonical chapters, specials, fractional chapters and count-derived numbered slots are merged and ordered by structured identity.
- Kitsu count 108 for Death Note creates 108 **display slots**, without bulk-creating canonical chapter rows, pretending a release exists, or displaying count-only information in a separate block. Nanatsu count 346 works identically.
- When actual chapter evidence arrives, the slot is replaced with the observed canonical identity. If the user explicitly opened a slot first, its deterministic provisional ID is reused and upgraded by reliable evidence; progress and downloads remain linked to that ID.
- The same Read/Download controls appear for count-only slots. Selecting one materializes or reuses exactly that identity under the same per-app chapter-mutation mutex as reconciliation. The reader then resolves content on demand; failure or missing content opens the existing retry/add-on selection flow.
- An exact parsed chapter identity in an already trusted Add-on title binding can resolve a count-only slot, even without an older chapter-variant mapping. Mismatched numbers cannot satisfy the slot; no automatic Add-on preference override was added.
- Removed the ordinary Provisional chip, count-only explanatory text and separate main-header provider-coverage paragraphs. Exceptional data-conflict diagnostics remain distinguishable.
- Moved the active source/language label off reading pages and into the Change Source sheet, which also marks the currently selected option.
- The architecture spec now explicitly distinguishes a provisional **display outline** from evidence-based canonical chapter structure.

## Automated evidence

- Reduced CI on the prior incremental code verified Domain tests, and an earlier App run (run 35778301512, eventually canceled by a newer push) logged `BUILD SUCCESSFUL`, including the new 108-slot ViewModel regression.
- Reduced CI at `81375df76906b4e11053dbcfc3f5893207de96f4` (run 35780522444): Format and App — Tsuzuki tests succeeded. A separate prior compile-only run 35778887736 passed Compile — App Compile and Domain tests.
- This commit explicitly requests a fresh **full CI**. Gate merge/APK on the completed full CI (App, Domain, Data, Core Common, format, SQLDelight, Supabase and release compilation).
- No new SQLDelight migration is needed: count-only slots are presentation values; only user-selected slots are materialized using the existing canonical-chapter schema.

## Post-APK human smoke

1. **Death Note (Kitsu):** expect chapter rows 1–108, with the same Download/Unread/read actions as real source-backed chapters and no separate reported-count explanation. Tap chapter 1 with no bound source: source selector should offer Retry and Add-ons, never a silently invented image. Install/link a trusted Add-on offering that chapter and retry; verify source selection and successful reading.
2. **Nanatsu no Taizai (Kitsu):** 346 numbered slots should appear as soon as metadata is cached/fetched, even if a chapter-evidence scan is still running. Revisit to compare cold/warm timing.
3. **Koe no Katachi:** Kitsu 64 plus observed fractional/extra chapters must form one ordered, deduplicated list; chapter 1, 1.1, 1.2, 1.5, 2 should remain in order. No ordinary Provisional chip.
4. **Hunter × Hunter:** check 0, 0.5, 1, 1.5 order and persisted reading state.
5. **One Punch-Man:** if Kitsu supplies a count, its count-only slots must coexist with source evidence beginning near 138. Opening an earlier slot must not misreport chapter 138 as the requested chapter. Verify only an actual alternative source can supply earlier pages.
6. **Reader:** no persistent floating Add-on/language label over artwork. Change Source must identify the active Add-on/language and highlight the selected option; switching still preserves previous pages on failure and asks separately about per-title Add-on/language preferences.
7. **Dandadan EN (unresolved external-image investigation):** compare the exact bytes, dimensions, MIME, order and displayed result of an affected English page against PT-BR and the original source. Saved-original versus displayed-page screenshots can distinguish an upstream image-splitting problem from viewer clipping. Do not claim fixed without source-byte evidence.
8. **Persistence/concurrency:** open the same metadata-only slot twice, reopen title details, verify a single canonical ID and preserved progress after source refresh or change. Test Download on a missing and on an available slot.

## Release boundary

Round 3's code contracts may be complete after full CI, but Dandadan EN original-image integrity and availability of One Punch-Man chapters before 138 remain **external-content evidence gaps**. Do not present them as fixed merely because CI passed. A signed bootstrap APK is a human-test artifact, not proof that live providers are healthy.
