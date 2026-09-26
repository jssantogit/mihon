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

## Confirmed divergence to protect before migration

**A stable provider URL reused for a different chapter is currently handled
differently by the two writers.** In
`ReconcileChapterInventory.execute`, an existing `ChapterVariant` found by
`(sourceId, sourceChapterId)` unconditionally reuses its old canonical chapter,
even if a provider's later label describes a different chapter or an explicit
incompatible volume. In `ReconcileChapterEvidence`, a reliable new observation
with the same producer-scoped external key is checked for conflict and may
rehome its evidence to a new canonical identity; an independently supported
old chapter remains available.

Before routing legacy Reader through the evidence writer, add a **red/green
cross-writer characterization** using one stable source URL changing from
volume 1 chapter 4 to volume 2 chapter 4 and another source that still
supports the old mapping. Assert the prior canonical ID/history remains
unchanged, no old Mihon variant silently reads the replacement chapter,
the conflicting mapping cannot auto-open an unverified chapter, and both
writers converge under one authoritative reconciliation policy. Prefer
quarantining the conflicting old operational variant and requesting source
confirmation over binding it to a known-wrong canonical chapter.

This is a code-path finding, not a report of a current real provider doing so.
The existing parity tests cover stable inputs and omitted rows; they do **not**
prove this conflict case.

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
- The baseline is still intentionally narrow. It does not cover the legacy Reader UI, reused external URLs, contradictory volume releases, a transaction spanning evidence and operational variants, or Android E2E. Do not use it as authorization to remove the older writer.

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
