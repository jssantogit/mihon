# Round 3 — source-independent chapter rows and post-APK UX

**Baseline:** `tsuzuki/bootstrap` @ `ae6ee77b383c1b59e4f9936f2bfe4c0d3ac9a4eb`  
**Exclusive branch:** `tsuzuki/post-apk-smoke-round-3`  
**Product decision:** User's post-APK smoke report on 2026-09-22 supersedes the previous detail-screen UX. Preserve the core canonical-ID and no-false-content invariants from the architecture spec.

## Smoke classification

| Case | Evidence | Disposition |
| --- | --- | --- |
| Dandadan EN cover/panels cropped | Three EN screenshots show cover artwork apparently fragmented across displayed pages; PT-BR works | Open upstream/raw-byte investigation. Do not claim Add-on defect or Reader defect without comparing the original bytes and dimensions against decoded frames. |
| Reader source preference prompt | Appeared | Passed |
| Reader source switch black screen | Not reproduced after repeated switching | Passed for observed scenario |
| One Punch-Man early chapters | Reading source still starts at ~138 | Genuine coverage gap until another provider supplies earlier chapters; do not silently replace the user's preferred Add-on. |
| Death Note | Kitsu provides count 108 but no chapter rows | **Primary Round 3 requirement** |
| Hunter × Hunter chapter order | Correct | Passed |
| Dandadan / Nanatsu initial loading | Faster; Nanatsu reports 346 but displays no chapter rows | Performance improved, chapter-list requirement open |
| Reader floating source label | Visible but intrusive | Move label exclusively to Change Source sheet |
| Koe no Katachi | Kitsu reports 64, Add-on records 72 including fractions and extra releases; provisional chips clutter the list | Unified chapter presentation; preserve known fractional/extra identities. |

## Product contract: one chapter list

A work has one visible chapter list regardless of how many providers can deliver pages. Kitsu/MAL metadata and Add-on chapter evidence contribute to **the same list**, never separate textual summaries that replace missing rows. A chapter row shows its number, title where known, read/unread state, offline download state and Download control. The presence of a row is not a claim that a reading source has the pages.

For a title such as Death Note whose only current data is `chapterCount=108`, render **108 numbered display slots** as the provisional catalog outline (1–108 by default) rather than an empty screen. Likewise render 346 outline slots for Nanatsu when only Kitsu count exists. No intrusive "provisional" badge or separate text-only count summary. Do not fabricate remote chapter IDs, release URLs or provider inventories.

**Identity safety:** count-only metadata does not prove the upstream numbering scheme. Keep count-based outline slots distinguishable from actual chapter evidence internally; do not create 108 irreversible, high-confidence CanonicalChapter records merely because Kitsu returned 108. Derive deterministic temporary slot keys from canonical title + expected regular number. Reuse a real canonical chapter for a slot as soon as chapter evidence for that identity exists; keep extras, decimals, chapter 0 and named specials as additional entries ordered by structured identity. Never overwrite user progress or merge titles by display name. Preserve old persisted canonical IDs if a provisional slot becomes a real chapter. If providers disagree on reported totals, choose a deterministic policy and retain per-provider counts in metadata, without declaring a conflicted count to be chapter evidence. Do not create millions of UI rows from obviously invalid counts; use lazy rendering and a bounded guard.

**Click behavior:** selecting a known row opens its normal canonical reader preparation; selecting a count-only slot first atomically materializes/reuses the local chapter identity and invokes exactly the same source-option resolution. If a matching enabled Add-on exists, the chapter is readable and its variant can be bound to the same canonical ID. If none exists, show the normal empty selector with Retry and Manage Add-ons; do not open an empty/black reader or secretly switch preferred Add-ons. The Download control follows the same on-demand resolution and remains visible even without a bound source. Read/unread and downloads must be canonical, not source-owned.

**UI:** remove the yellow/outlined provisional chip from ordinary chapter rows; keep exceptional conflicting identity diagnostic discoverable but unobtrusive. Remove redundant "Kitsu: N chapters" and "Reported chapter count: ... structure unavailable" text on the main detail screen once slots are present; clean up duplicated Add-on coverage summaries, exposing diagnostics separately only when useful. The active Add-on/language label appears only inside Change Source, never as a permanent reading-overlay chip.

## Implementation sequence and test gates

1. Remove reader floating source label, display it inside Change Source, and remove ordinary provisional chips. Add tests where feasible.
2. Extract a pure, unit-testable chapter-outline union in the domain layer: real canonical identities + metadata count-based numbered slots. Test Death Note 108, Nanatsu 346, Koe 64 + 72 Add-on observations, Hunter 0/0.5 ordering, identical identity reuse, disagreement across count providers, invalid/zero counts and unchanged existing chapter IDs. Derive placeholders cheaply without 346 network/database queries on the initial render.
3. Wire the union into title-detail UI and local cached-first refresh. No metadata-only empty list. Clear obsolete count labels, use the same row layout and one LazyColumn.
4. Implement a serialized on-demand slot materializer in the domain/data path, deduplicated by canonical identity and safe under repeated taps/parallel refresh, with deterministic stable IDs where appropriate. Route both Read and Download through it; preserve canonical progress and select source on demand. Test no-source error, partial failure, newly linked Add-on and existing user state.
5. Investigate Dandadan EN original image bytes independently of image fitting; assess source/network split vs viewer scaling. Treat original-file comparison as a human-device evidence gate.
6. Affected CI after each slice; full CI including app/data/domain tests, SQLDelight, Supabase and release compilation before bootstrap merge. APK only after green full CI and authorization.

## Known architectural change

Current spec §4.3 correctly says *a count is not proof of the actual chapter numbering structure*. Round 3 changes the **display** contract, not the evidence contract: a reported count may supply visible provisional chapter slots so the user can browse the work before any content source is installed. Slots must remain semantically distinct from confirmed source/editorial chapter evidence until reconciled, even though they share the exact same chapter-row UI.
