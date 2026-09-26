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

The current Android focused lane has validated decoded image widgets and
screen-capture configuration, but has not yet proven a foreground Reader
screenshot and successful A-to-B source switch. Synthetic 169/163 inventory
tests are not proof of current live MangaDex causes.
