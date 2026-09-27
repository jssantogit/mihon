# Tsuzuki chapter reconciliation: production caller map and migration gate

Status: design map only; **Phase D is not implemented**. This file describes the
active paths in PR #21. It does not authorize merging while Android acceptance
or real-provider evidence remains incomplete.

## Current production paths

| Entry point | Refresh and input | Canonical writer | Result |
| --- | --- | --- | --- |
| `ReaderViewModel.attachCanonicalSessionForLegacy` | On an unmapped legacy Mihon chapter, calls `RefreshCanonicalChapters.execute(title, mappingId)` through `ChapterInventoryGateway` / `MihonChapterInventoryGateway` | `ReconcileChapterInventory` | `CanonicalChapterRepository.upsertBatch` stores canonical chapters and Mihon operational `ChapterVariant` rows; Reader then finds the variant by source ID and URL. |
| `CanonicalTitleScreenModel.refreshInBackground` | `RefreshChapterEvidence.execute(title)` | `ReconcileChapterEvidence` | Transactional canonical chapter and persisted editorial/Add-on evidence; broad refresh currently remains. |
| `ContentSelectorScreenModel.refreshAfterBindings` | `RefreshChapterEvidence.executeForBinding(binding)`, bounded to two selected bindings | `ReconcileChapterEvidence` | Same canonical chapter/evidence transaction; avoid refreshing all providers. |
| Targeted initial Reader discovery | `DiscoverReadableChapter`, `ResolveChapterContent` and scoped evidence refresh | `ReconcileChapterEvidence` when refresh is necessary | Uses verified per-chapter options; a verified option is not proof that full image bytes have rendered. |

`ReconcileChapterInventory` and `ReconcileChapterEvidence` already share the
conservative `CanonicalChapterCandidateResolver` and explicit volume policy,
but they remain different canonical writers. Treat them as potentially
divergent until equivalent outcomes are established by the tests below.

## Reused source URLs: safety guard before writer unification

The original legacy reconciler reused a persisted `ChapterVariant` solely
because `(sourceId, sourceChapterId)` was unchanged, even when a later
observation described a reliably different chapter or explicitly contradictory
volume. The evidence reconciler already detects those conflicts and can remap
source-scoped evidence to the newly supported identity.

**The acceptance branch now fails closed in the legacy writer:** it parses
existing source-key observations, checks reliably contradictory chapter
identities and explicitly incompatible non-null volumes, and rejects the
affected inventory *before* committing any batch. Existing canonical IDs,
progress and independent source variants remain intact. It also rejects a
persisted variant pointing to another canonical title. This guard prevents
silently reaffirming a known-wrong old association, but does **not** resolve
the reused source URL automatically: old operational variants remain on disk
until a shared, transactionally safe evidence/variant migration is implemented.

A RED→GREEN regression reproduces an existing URL changing from volume 1
chapter 4 to volume 2 chapter 4 while a second source still supports the old
chapter. Two older tests were updated to assert fail-closed behavior instead
of unconditional trust in the reused URL. The [focused Domain and App CI
36271257460](https://github.com/jssantogit/mihon/actions/runs/36271257460)
passed on equivalent production and test source blobs; skipped Format,
Release and migration jobs in that run do not count as validation.

Full unification is still blocked on legacy Reader acceptance, operational
variant projection in the same database transaction as evidence, and
conflicting-key compatibility coverage.

## Migration sequence (after Phase A/C acceptance)

1. Record equivalence fixtures against *both existing writers*: fractional
   chapters (0/0.5/1), same number in different volumes, two languages
   pointing to one chapter, reused external keys, partial inventory, empty
   or disabled sources, refresh concurrency, and an unmatched old Mihon
   chapter. Assert canonical IDs and exact operational variant mapping.
2. Implement a narrow legacy `SourceChapterInventory` adapter producing
   source-scoped provisional `ChapterEvidence` with stable keys and explicit
   volumes. A legacy Mihon mapping may exist without a new `ContentBinding`;
   do not silently discard these readers during migration.
3. Route canonical identity and evidence persistence through
   `ReconcileChapterEvidence`. Ensure the required `ChapterVariant` for a
   legacy Reader is projected from its **persisted mapped evidence** with the
   original Mihon source/mapping identity, in the same relevant database
   transaction. Do not create a second fallback canonical writer.
4. Migrate `ReaderViewModel.attachCanonicalSessionForLegacy` and then
   `RefreshCanonicalChapters` callers behind the adapter. Preserve
   materialized Mihon IDs and successful loaded-page sessions when
   reconciliation or a provider fails; never guess the mapping.
5. Remove `ReconcileChapterInventory` as an independent canonical writer
   only after all callers and equivalence tests have passed. Preserve
   compatibility adapters that serve genuinely operational Mihon rows.

## Executed pre-migration equivalence baseline

- `ReconcileChapterEvidenceTest` now contains a dual-writer characterization using two explicit volume identities and two Mihon source IDs. The comparison seeds the *same existing canonical chapter IDs* in isolated legacy/evidence stores, checks both writers' source-to-chapter decisions and verifies that a repeated refresh retains legacy variant IDs.
- The Domain job passed in [CI 36267018356](https://github.com/jssantogit/mihon/actions/runs/36267018356) with byte-identical matcher, both reconcilers, volume/label parsers, and test sources on the acceptance PR and validation branch.
- A second parity test exercises chapters 0, 0.5 and 1, then refreshes with the middle observation **omitted**. Both writers preserve the middle chapter; the legacy source variant and persisted evidence retain their original mappings. [Domain tests passed in CI 36267649886](https://github.com/jssantogit/mihon/actions/runs/36267649886).
- A third parity test seeds two provider mappings for the same preexisting volume 1 chapter 4, empties one source's inventory and then both inventories, and asserts that both canonical writers preserve the established chapter ID, both legacy variant IDs and the mapped evidence for both sources. Domain, Data, App, Core, SQLDelight, Supabase and Format passed in the [PR-equivalent full verification 36273234659](https://github.com/jssantogit/mihon/actions/runs/36273234659); Release and CI Gate also passed; separate compile matrix and Native Package Gate were skipped by the planner. This is not an assertion about live provider completeness.
- A fourth dual-writer test covers **two source languages** (English and pt-BR) sharing one preexisting volume-1 canonical chapter while retaining separate legacy `ChapterVariant` IDs and language metadata. It reverses refresh ordering and later omits the pt-BR row; neither writer may drop the previous mapping. The [Domain job in CI 36282164099](https://github.com/jssantogit/mihon/actions/runs/36282164099) passed on byte-identical PR source and test blobs; the remaining full CI jobs require separate checking.
- The baseline is still intentionally narrow. It does not cover the legacy Reader UI, reused external URLs, contradictory volume releases, a transaction spanning evidence and operational variants, or Android E2E. Do not use it as authorization to remove the older writer.

## Staged legacy inventory evidence adapter — not yet used by the Reader

The acceptance PR now includes `LegacyInventoryEvidenceAdapter`, which maps a previously materialized Mihon source inventory to `ADDON_PROVISIONAL` evidence **without requiring a new ContentBinding**. Its producer scope includes the canonical title and Mihon source ID, so the same relative URL in a different title/source cannot accidentally reuse evidence; its generated evidence ID is deterministic across refresh order and label changes. Only explicit, unambiguous volume-prefix evidence becomes `volume`; the old numeric hint remains a hint, never verified editorial authority. Identical repeated chapter URLs are deduplicated; contradictory duplicate URL observations and mismatched source/mapping IDs fail closed before any write. Blank source ID may use a nonblank legacy source URL; both blank fail.

The [initial RED CI 36282830973](https://github.com/jssantogit/mihon/actions/runs/36282830973) executed all four new adapter tests and they failed against the intentionally unimplemented adapter. The subsequent [Domain CI 36282997303](https://github.com/jssantogit/mihon/actions/runs/36282997303) passed those tests and a new end-to-end test through the existing evidence reconciler: an English source reuses a URL for volume 2 while a Portuguese source retains independent support for volume 1, preserving both canonical IDs. Spotless reported one whitespace layout difference in the adapter; the corrected implementation is under [CI 36283160529](https://github.com/jssantogit/mihon/actions/runs/36283160529) passed Domain, Data, App, Core, SQLDelight, Supabase, Format, Release and CI Gate; separate Compile/Native gates were skipped by the planner.

**The adapter is still not connected to the legacy Reader.** A staged projector now writes evidence and variants in one SQLDelight transaction and passes an injected second-variant failure/retry regression. Reader source-mapping authority, concurrency and lifecycle still gate cutover.

## Staged atomic projection — not production Reader routing

The acceptance PR now includes `ReconcileLegacyChapterEvidence`. After source-scoped provisional evidence is created by the legacy adapter, `ReconcileChapterEvidence.executeAndProject` reconciles canonical chapters/evidence and projects only the **persisted mapped evidence** to Mihon `ChapterVariant` rows. Everything runs within the same SQLDelight transaction and chapter mutation gate. An unmapped observation creates no variant; if an already-mapped key becomes ambiguous the projector fails closed and the transaction rolls back. Stable operational variant IDs and source metadata are retained across reorder and explicit-volume reassignment; independent support for the old volume is preserved.

The [RED JDBC CI 36284495531](https://github.com/jssantogit/mihon/actions/runs/36284495531) confirmed three new regressions fail against the deliberately unimplemented projector. [Scoped GREEN CI 36284727291](https://github.com/jssantogit/mihon/actions/runs/36284727291) passed Domain, Data, App, Format and CI Gate. The real SQLite foreign-key regression fails on the **second variant** after an earlier valid variant and newly written canonical/evidence rows; it confirms complete rollback, preservation of preexisting state and successful retry with the mapping fixed. Scope-planned migration/Release/Supabase/Native and separate Compile jobs were skipped, not approved; a full CI run on byte-identical PR files remains required.

This is the transaction building block, **not single-writer cutover**. Validate mapping/title ownership, concurrent refresh, legacy Reader missing-mapping entry and process-restart recovery, performance and final Android 8/8 before deleting the independent legacy writer. No distribution APK or merge is authorized by the staged API.

## Mapping authority and cross-writer serialization — scoped regression evidence

The staged legacy projection now reads the persisted `SourceTitleMapping` **inside** its canonical/evidence/variant transaction. It accepts an inventory only if the mapping ID belongs to the same canonical title, the Mihon source ID and materialized manga ID match, the mapping is not unavailable and nonblank inventory language matches. A rejected inventory rolls back staged canonical/evidence writes and never publishes an operational variant. [RED JDBC CI 36285954535](https://github.com/jssantogit/mihon/actions/runs/36285954535) reproduced three previously accepted invalid inventories; [GREEN scoped CI 36286206067](https://github.com/jssantogit/mihon/actions/runs/36286206067) passed Data, Domain, App, Format and CI Gate.

While both writers coexist, the injected `ReconcileChapterInventory` now locks the same AppScope `ChapterMutationGate` already used by `ReconcileChapterEvidence`. A deterministic test held the gate to simulate an active competing reconciliation: the unprotected legacy writer wrongly completed before release ([RED Domain 36286180551](https://github.com/jssantogit/mihon/actions/runs/36286180551)); the corrected writer waited and Domain/App/CI Gate passed in [scoped GREEN 36286381163](https://github.com/jssantogit/mihon/actions/runs/36286381163). This is serialization proof, **not** full end-to-end proof of simultaneous Reader/detail UI refresh.

**Identity and ordering gates now covered:** the neutral inventory DTO records the exact source title URL. Persisted title/source/URL/manga/availability validation runs inside the same projection transaction, including empty inventories. The staged projector serializes with the legacy writer through the shared mutation gate; old evidence cannot overwrite a newer persisted observation. The Mihon gateway now attaches the original fetch-start timestamp, which stays unchanged in cached inventories; the legacy evidence adapter retains it through later reconciliation and an equal-timestamp conflicting legacy release is rejected while identical cache replays remain idempotent.

[PR-equivalent full CI 36313408103](https://github.com/jssantogit/mihon/actions/runs/36313408103) passed mapping, locking and empty-projection coverage. [RED provenance CI 36313988598](https://github.com/jssantogit/mihon/actions/runs/36313988598) exposed missing provider fetch timestamps in App/Domain and a cached stale overwrite in real Data/JDBC. [Scoped GREEN 36314684084](https://github.com/jssantogit/mihon/actions/runs/36314684084) passed App/Domain after preserving the original fetch clock. [RED same-millisecond JDBC 36314850321](https://github.com/jssantogit/mihon/actions/runs/36314850321) then demonstrated an ambiguous equal-time rehome. [Full CI 36315380750](https://github.com/jssantogit/mihon/actions/runs/36315380750) passed Domain, Data, App, Core Common, Format, SQLDelight, Supabase, Release Compile and CI Gate with the corrected Mihon-legacy-only guard; separate Compile/Native jobs were skipped by the planner. Verified that all eight PR source/test blobs are byte-identical to the disposable green branch; none of its CI workflows were promoted.

**Remaining before cutover:** simultaneous real Reader/detail refresh and invalidated in-flight cache, staged-projection benchmark (100/500/1,000 observations), Android process restart and a new 8/8 acceptance after routing production Reader. Live-provider inventory and physical-device evidence remain separate. The independent legacy Reader writer has not been removed; neither PR is merged.

## Acceptance checks

- Fault-inject between canonical chapter, evidence and operational variant
  writes: rollback all affected rows without deleting old correct mappings.
- Replays and simultaneous legacy/canonical refresh must retain stable
  IDs, avoid double variants and preserve chapter order, volumes, history
  and preferred source.
- Re-run 100/500/1,000-observation benchmarks against PR #20. Never
  restore the previous per-observation full-title evidence reads.
- An unavailable extension or a chapter missing from one source must not
  delete other-source evidence. A low-confidence observation must not
  create a falsely verified reading option.
- No broad all-source fetch is introduced by a targeted per-binding refresh.
- Run Domain/Data/App, SQLDelight migration, and all eight instrumented
  Reader tests on the **same final production implementation SHA**.
  Distinguish a compiled or skipped test from an executed Android pass.

## Android emulator acceptance now completed; writer migration still gated

[Final offline emulator run 36280293418](https://github.com/jssantogit/mihon/actions/runs/36280293418) executed **all eight named tests, 8/8 PASS**, with strict instrumentation/result parsing and real on-screen red/blue pixels linked to attached decoded page views: A→B retains index 4; empty/503 replacement retains prior session; 10→3 pages clamps 8→2; retired callbacks cannot overwrite newer state; Activity recreation restores index 6; repeated A↔B preserves one history row; slow source cannot block healthy source; canceled discovery cannot mutate the active reader. The [full CI 36280293408](https://github.com/jssantogit/mihon/actions/runs/36280293408) passed Domain/Data/App/Core, SQLDelight migration, Supabase, Format, Release and CI Gate; separate Compile matrix and Native Package Gate were skipped, not approved. The disposable test SHA [`bd95095`](https://github.com/jssantogit/mihon/commit/bd95095e9a33127b032f42944fb13ffcba9d1de9) has byte-identical blobs to PR #21's three production Reader files and instrumented test; CI routing/workflow files differ to trigger the offline run. An exact-title Pixel Launcher system-ANR dismissal was added to the test fixture without relaxing focus or screenshot requirements.

**This closes Phase A's eight offline emulator cases only.** The production legacy Reader entry through missing variant mapping, real-provider inventory, process-kill recovery, physical-device visual evidence and Phase D's stage-to-production Reader cutover and its process-restart validation still lack acceptance. The two reconcilers remain separate; do not merge or delete one based on Android 8/8 alone. Synthetic 169/163 inventory fixtures do not explain a current live MangaDex discrepancy.
