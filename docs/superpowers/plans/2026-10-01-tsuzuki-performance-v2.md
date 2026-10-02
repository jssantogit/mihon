# Tsuzuki Performance V2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Search/Discovery and canonical Detail fast under the real multi-provider workload while preserving Tsuzuki's canonical identity, chapter safety, Reader, and provider/source independence.

**Architecture:** Convert expensive flows from "recompute everything on every open" to "local snapshot -> freshness decision -> shared/background revalidation -> delta/no-op processing -> progressive enrichment." Chapter work gets persisted freshness/fingerprints, indexed reconciliation and cheaper SQL projections; metadata/ratings get snapshot caches and single-flight; Search/Discovery consume those shared caches and cancel obsolete enrichment.

**Tech Stack:** Kotlin, Coroutines/Flow, Metro DI, SQLDelight, OkHttp/Mihon source runtime, GitHub Actions CI v2.1.

**Spec:** `docs/superpowers/specs/2026-10-01-tsuzuki-performance-v2-design.md`

## Global Constraints

- Never run local Gradle. Validation is GitHub Actions plus final physical APK smoke.
- Preserve canonical work identity independent of provider/source.
- Preserve reading-source independence from metadata providers.
- Preserve current fail-closed chapter evidence behavior, including unsafe regular chapter 0 corroboration.
- Editorial chapter counts never become reading inventory.
- Stable canonical chapter IDs and user-owned progress/download state must survive every optimization.
- Stale inventories must never overwrite newer persisted observations.
- Explicit user refresh bypasses freshness/no-op shortcuts.
- Identity matching rules for metadata/ratings must not be broadened.
- No new providers or unrelated UI redesign in this work.
- Every performance shortcut must have a correctness regression test first.

## Review Focus

1. **Provider/add-on configuration changes:** cached metadata, ratings, freshness and inventory fingerprints must be invalidated when the enabled/configured producer set changes.
2. **Stale concurrent refresh completion:** an older provider result must not overwrite a newer snapshot or freshness marker.
3. **Partial provider failure:** cached usable metadata/chapters remain visible while failed optional providers do not poison the snapshot.
4. **Large duplicate inventories:** 5k–10k evidence from several sources must preserve the same canonical graph and stable IDs without quadratic behavior.
5. **Query churn:** Search enrichment from an old query must not update the new query's visible state.

---

### Task 1: Establish realistic performance regression baselines

**Files:**
- Modify: `data/src/test/java/tachiyomi/data/tsuzuki/ChapterEvidenceReconciliationBenchmarkTest.kt`
- Modify: `domain/src/test/java/tachiyomi/domain/tsuzuki/chapter/evidence/ReconcileChapterEvidenceTest.kt`
- Modify: `domain/src/test/java/tachiyomi/domain/tsuzuki/catalog/interactor/SearchIntegrationsTest.kt`
- Modify: `domain/src/test/java/tachiyomi/domain/tsuzuki/integration/interactor/ResolveCanonicalMetadataTest.kt`
- Modify: `app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/detail/CanonicalTitleScreenModelTest.kt`

**Interfaces:**
- Consumes: current reconciliation, metadata resolution and Search enrichment APIs.
- Produces: benchmark fixtures for 1k/5k/10k evidence, multi-producer duplicate evidence fixture, and observable counters/timings that later tasks must improve without changing results.

- [ ] **Step 1: Extend benchmark workloads**
  - Add 5,000 and 10,000 observation cases.
  - Add a multi-producer fixture where several provider inventories describe the same canonical chapter identities.
  - Record elapsed time plus SQL execute/query counts separately for seed, reconcile, and repeated unchanged reconciliation.
  - Keep benchmarks opt-in through environment flags.

- [ ] **Step 2: Add behavioral baseline tests**
  - Assert repeated identical evidence preserves canonical IDs and mappings.
  - Assert stale observations cannot replace newer evidence.
  - Assert Search enrichment currently performs only one logical result update per accepted query state.
  - Assert Detail cached-first state can be observed before refresh completion.

- [ ] **Step 3: Push the RED/characterization commit**
  - Commit message: `test: expand performance v2 baselines`
  - Use GitHub Actions; do not run Gradle locally.
  - Expected: characterization tests pass; new tests intended for later APIs may be added only when they fail for the missing behavior they specify.

- [ ] **Step 4: Capture baseline benchmark artifact**
  - Run the opt-in SQLDelight benchmark through CI with `TSUZUKI_CHAPTER_BENCHMARK=true` and a revision label.
  - Preserve the report in CI artifacts or a committed research report only if CI cannot retain it.
  - The baseline must include 1k/5k/10k elapsed time and SQL counts before optimization.

---

### Task 2: Persist chapter-refresh freshness and inventory fingerprints

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/refresh/ChapterRefreshSnapshot.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/refresh/ChapterRefreshSnapshotRepository.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/chapter/ChapterRefreshSnapshotRepositoryImpl.kt`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_chapter_refresh_snapshot.sq`
- Create: `data/src/main/sqldelight/tachiyomi/migrations/38.sqm`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/evidence/RefreshChapterEvidence.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/MihonChapterInventoryGateway.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/addon/MihonChapterProbeProvider.kt`
- Test: `data/src/test/java/tachiyomi/data/tsuzuki/chapter/ChapterRefreshSnapshotRepositoryImplTest.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/chapter/evidence/RefreshChapterEvidenceTest.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/addon/MihonChapterProbeProviderTest.kt`

**Interfaces:**
- Produces:
  - `ChapterRefreshSnapshotRepository.getTitleFreshness(canonicalTitleId)`
  - per-binding/source fingerprint read/write methods;
  - `RefreshChapterEvidence.execute(canonicalTitleId, forceRefresh: Boolean = false)`.
- Consumes: stable inventory fields and current enabled add-on/provider configuration fingerprint.

- [ ] **Step 1: Write failing persistence/freshness tests**
  - Persist/read a title freshness timestamp and provider-config fingerprint.
  - Persist/read per-binding inventory fingerprint.
  - Verify stale completion cannot replace a newer fingerprint timestamp.
  - Verify migration creates the new table/indexes without changing existing rows.

- [ ] **Step 2: Define the inventory fingerprint**
  - Hash stable source identity plus chapter identity-relevant fields: source chapter key/URL, raw label, numeric hint, volume-relevant label content, language and source mapping identity.
  - Exclude fetch completion time and volatile diagnostics fields.
  - Test order normalization: identical inventory in different list order must produce the same fingerprint when source order is not semantically meaningful.

- [ ] **Step 3: Add freshness policy**
  - Normal refresh checks the persisted title snapshot plus current producer/config fingerprint.
  - Fresh snapshot returns success without probing providers.
  - `forceRefresh=true` bypasses the freshness shortcut.
  - A producer/config fingerprint mismatch makes the snapshot stale immediately.

- [ ] **Step 4: Add unchanged-inventory no-op**
  - When a fetched inventory fingerprint equals the last successfully reconciled fingerprint, skip evidence reconstruction/reconciliation/persistence for that binding.
  - Do not update the persisted fingerprint until the corresponding reconciliation has succeeded.

- [ ] **Step 5: Verify through CI**
  - Push with a scoped CI trigger covering Domain, Data, App and SQLDelight migrations.
  - Required green: freshness tests, snapshot repository tests, chapter probe tests, migration verification.

---

### Task 3: Make reconciliation/persistence scale approximately linearly

**Files:**
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/evidence/ReconcileChapterEvidence.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/evidence/ChapterEvidenceRepository.kt`
- Modify: `data/src/main/java/tachiyomi/data/tsuzuki/chapter/ChapterEvidenceRepositoryImpl.kt`
- Modify: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_chapter_evidence.sq`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/repository/CanonicalChapterRepository.kt`
- Modify: `data/src/main/java/tachiyomi/data/tsuzuki/CanonicalChapterRepositoryImpl.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/chapter/evidence/ReconcileChapterEvidenceTest.kt`
- Test: `data/src/test/java/tachiyomi/data/tsuzuki/ChapterEvidenceRepositoryImplTest.kt`
- Modify benchmark: `data/src/test/java/tachiyomi/data/tsuzuki/ChapterEvidenceReconciliationBenchmarkTest.kt`

**Interfaces:**
- Produces lightweight preloaded reconciliation indexes and bulk lookup/projection repository methods.
- Preserves the external `ReconcileChapterEvidence.execute(canonicalTitleId, evidence)` contract.

- [ ] **Step 1: Add regression tests for indexed equivalence**
  - Build old-style expected graphs for duplicate, cross-producer, volume-ambiguous, provisional chapter 0, stale-evidence and stable-ID cases.
  - The optimized reconciler must produce byte-for-byte equivalent logical mappings/state.

- [ ] **Step 2: Pre-index persisted state once**
  - Build maps for evidence ID, producer/external key, mapped canonical chapter, producer/source and canonical identity/volume.
  - Precompute independent-support counts used by conflict decisions.
  - Cache parser results by raw label + numeric hint for the refresh.

- [ ] **Step 3: Remove full-list scans inside the per-observation loop**
  - Replace repeated `.values.any`, `.filter`, `.singleOrNull` and cross-producer scans with the indexes.
  - Update indexes incrementally as staged evidence/chapter mutations are accepted.

- [ ] **Step 4: Add bulk repository lookups**
  - Replace per-write external-key point queries in `upsertBatch` with one title snapshot and, only where necessary, one bulk global-key query.
  - Keep the unique database index authoritative for collision safety.
  - Add a lightweight batch upsert return path that does not re-query the full title after every write.

- [ ] **Step 5: Re-run 1k/5k/10k benchmark**
  - Compare elapsed time and SQL counts against Task 1.
  - Acceptance: no correctness regression and clear reduction in SQL count / superlinear growth.
  - Do not encode fragile device-time thresholds into unit tests; record relative before/after in the benchmark report.

---

### Task 4: Make Detail local projection cheap and freshness-aware

**Files:**
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/evidence/ChapterEvidenceRepository.kt`
- Modify: `data/src/main/java/tachiyomi/data/tsuzuki/chapter/ChapterEvidenceRepositoryImpl.kt`
- Modify: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_chapter_evidence.sq`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/detail/CanonicalTitleScreenModel.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/download/interactor/GetCanonicalChapterDownloadState.kt` only if a batch compatibility seam is required.
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/detail/CanonicalTitleScreenModelTest.kt`
- Test: `data/src/test/java/tachiyomi/data/tsuzuki/ChapterEvidenceRepositoryImplTest.kt`

**Interfaces:**
- Produces:
  - lightweight `mappedChapterIds(canonicalTitleId)`;
  - aggregate add-on/provider coverage query;
  - Detail refresh call that passes `forceRefresh=false` for normal open and `true` for explicit refresh.

- [ ] **Step 1: Write failing Detail projection tests**
  - Opening an existing fresh title must not call full `getByCanonicalTitleId` evidence hydration.
  - Opening a fresh title must not probe chapter providers.
  - Explicit refresh must probe despite freshness.
  - Cached chapters/progress/download state must render immediately.

- [ ] **Step 2: Add lightweight SQL projections**
  - Query mapped canonical chapter IDs directly.
  - Query coverage/counts grouped by producer/add-on without materializing evidence payloads.
  - Use those results in `loadLocalState`.

- [ ] **Step 3: Remove whole-list legacy download fan-out**
  - Use canonical download state as the normal path.
  - Run legacy compatibility checks only for chapters whose canonical state is unresolved, preferably via a bounded/batch seam.
  - Never issue one compatibility lookup for every known chapter on ordinary Detail refresh.

- [ ] **Step 4: Wire freshness**
  - `start()` shows local state then calls a freshness-aware background refresh.
  - `refresh()` is explicit and forces chapter/metadata revalidation.
  - A fresh normal open settles `isRefreshing=false` without network work.

- [ ] **Step 5: CI validation**
  - Required green: Detail model tests, chapter repository tests, Reader/domain regression tests affected by chapter state.

---

### Task 5: Persist canonical metadata snapshots and share provider work

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/integration/model/CanonicalMetadataSnapshot.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/integration/repository/CanonicalMetadataSnapshotRepository.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/integration/CanonicalMetadataSnapshotRepositoryImpl.kt`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_metadata_snapshot.sq`
- Create: `data/src/main/sqldelight/tachiyomi/migrations/39.sqm`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/integration/interactor/ResolveCanonicalMetadata.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/metadata/interactor/RefreshReportedChapterCounts.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/detail/CanonicalTitleScreenModel.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/integration/interactor/ResolveCanonicalMetadataTest.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/metadata/interactor/RefreshReportedChapterCountsTest.kt`
- Test: new data repository tests.

**Interfaces:**
- Produces:
  - `CanonicalMetadataSnapshotRepository.get/upsert/invalidate`;
  - `ResolveCanonicalMetadata.execute(canonicalTitleId, forceRefresh: Boolean = false)`;
  - shared provider-detail result inside one resolution cycle.
- Snapshot key includes canonical title plus provider/config fingerprint.

- [ ] **Step 1: Add snapshot serialization/persistence tests**
  - Round-trip all resolved metadata fields, native ratings and Tsuzuki Rating inputs needed for immediate rendering.
  - Verify provider/config fingerprint mismatch invalidates reuse.
  - Verify stale snapshot remains readable while revalidation is attempted.

- [ ] **Step 2: Add single-flight metadata resolution**
  - Simultaneous calls for the same canonical title/config share one in-flight deferred.
  - Cancellation of one waiter does not cancel the owner unless no consumers remain or the operation itself is invalidated.
  - Failed optional provider resolution does not delete the last usable snapshot.

- [ ] **Step 3: Eliminate duplicate editorial details calls**
  - During one metadata resolution, retain each provider's `getDetails` payload.
  - Project `ReportedChapterCount` from the same payload when it contains chapter count.
  - Keep `RefreshReportedChapterCounts` for callers that explicitly need standalone refresh, but give Detail one shared orchestration path instead of two network passes.

- [ ] **Step 4: Use stale-while-revalidate in Detail**
  - Persisted metadata snapshot renders with the initial local state.
  - Stale snapshot triggers background resolution.
  - Fresh snapshot performs no provider call on normal open.
  - Explicit refresh forces revalidation.

- [ ] **Step 5: Migration/full data CI**
  - Verify SQLDelight migration 39 and metadata repository tests.
  - Run affected Domain/Data/App CI.

---

### Task 6: Add rating identity/result cache and single-flight

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/cache/RatingEnrichmentCache.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/integration/DefaultRatingEnrichmentCache.kt` or place in domain if no Android dependency is needed.
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/interactor/SearchIntegrations.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/integration/interactor/ResolveCanonicalMetadata.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/catalog/interactor/SearchIntegrationsTest.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/integration/interactor/ResolveCanonicalMetadataTest.kt`

**Interfaces:**
- Produces cache keys scoped by canonical/catalog identity + provider + provider/config fingerprint.
- Supports positive result, short-lived negative result, in-flight join, title/provider invalidation.

- [ ] **Step 1: Write cache contract tests**
  - Exact external ID already present -> no resolver call.
  - Same work/provider requested concurrently -> one resolver/rating request.
  - Positive cache reused across Search/Discovery/Detail.
  - Negative miss expires sooner than positive result.
  - Config/provider enablement change invalidates prior entries.

- [ ] **Step 2: Implement fast identity paths**
  - Prefer verified/existing external IDs.
  - Prefer native score already supplied by the catalog provider.
  - Invoke strict external-ID recovery only when required.
  - Do not change matching thresholds or corroboration rules.

- [ ] **Step 3: Share enrichment results**
  - Search/Discovery and Detail use the same cache boundary.
  - Keep native ratings and aggregate composition exactly equivalent to current logic.

---

### Task 7: Make Search/Discovery enrichment prioritized and cancelable

**Files:**
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/interactor/SearchIntegrations.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/interactor/SearchCatalog.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/interactor/GetDiscoverFeed.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/catalog/CatalogScreenModel.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/catalog/interactor/SearchIntegrationsTest.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/catalog/interactor/SearchCatalogTest.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/catalog/CatalogScreenModelTest.kt`

**Interfaces:**
- Produces an enrichment scheduler that accepts ordered items and emits progressive updated items/pages.
- Search model owns one enrichment job per active query and cancels it on query change.
- Discovery shares cache/single-flight with Search.

- [ ] **Step 1: Add query-churn regression**
  - Query A starts blocked enrichment.
  - Query B replaces it.
  - Completing A afterward must not mutate B state.

- [ ] **Step 2: Prioritize enrichment order**
  - Enrich first page items in display order with a small concurrency window.
  - Publish updates progressively rather than waiting for every item.
  - Do not re-enrich duplicate canonical/provider work across Trending and Popular.

- [ ] **Step 3: Add separate backpressure gates**
  - Network identity/rating work gets its own concurrency bound.
  - CPU-only composition does not consume network permits.
  - Cancellation propagates immediately when query/result generation becomes obsolete.

- [ ] **Step 4: Provider-specific batching hook**
  - Define an optional batching capability only for providers that truly support multi-item APIs.
  - No provider is forced into fake batching.
  - If no current provider supports a safe batch endpoint, land the interface/tests only when it has an actual consumer; otherwise omit it as YAGNI.

---

### Task 8: Progressive safe chapter publication

**Files:**
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/evidence/RefreshChapterEvidence.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/detail/CanonicalTitleScreenModel.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/evidence/ReconcileChapterEvidence.kt` only if staged-safe commit support is required.
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/chapter/evidence/RefreshChapterEvidenceTest.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/detail/CanonicalTitleScreenModelTest.kt`

**Interfaces:**
- Produces staged refresh events/state: first safe canonical inventory available, additional providers still running, refresh complete.
- Safety remains defined by existing reconciler rules, not by arrival order.

- [ ] **Step 1: Add staged-safety tests**
  - First provider returns safe chapters quickly; second provider blocks.
  - Safe chapters become visible before second provider completes.
  - Unsafe provisional chapter 0 remains hidden until independent corroboration arrives.
  - A later conflicting provider can mark/reconcile state according to existing rules without changing user-owned IDs.

- [ ] **Step 2: Stage provider groups**
  - Reconcile/publish newly available safe evidence in bounded stages.
  - Preserve stale-observation ordering and mutation gates.
  - Do not mark the title freshness snapshot complete until all scheduled providers for that refresh have settled.

- [ ] **Step 3: Keep Reader/content-option invalidation scoped**
  - Invalidate only affected title/add-on mappings when a stage changes the chapter graph.
  - Avoid invalidating unchanged content option caches after a no-op stage.

---

### Task 9: Conservative prefetch and final simplification

**Files:**
- Modify only existing Home/Library/Continue Reading entry points that already own visible item lists.
- Add a small prefetch coordinator only if shared cache warming cannot be expressed through existing interactors.
- Test affected screen models/coordinator.

**Interfaces:**
- Prefetch is best-effort, cancelable, bounded and consumes the same metadata/rating caches as foreground work.

- [ ] **Step 1: Add bounded prefetch tests**
  - Only visible/near-visible items are queued.
  - Foreground request joins existing prefetch instead of duplicating it.
  - Leaving the screen cancels queued work.
  - Prefetch failure never surfaces as a user-facing error.

- [ ] **Step 2: Implement only if benchmarks show foreground cache misses remain material**
  - Continue Reading first.
  - Visible Library/Home cards second.
  - Do not background-sync the full library.

- [ ] **Step 3: Simplify while green**
  - Remove superseded duplicated refresh paths introduced by earlier architecture.
  - Keep one source of truth for freshness, metadata snapshot and rating cache invalidation.

---

### Task 10: Full verification, review, merge discipline and physical APK acceptance

**Files:**
- Modify: `docs/superpowers/specs/2026-10-01-tsuzuki-performance-v2-design.md` only if implementation discoveries require an approved invariant clarification.
- Add/update performance research report if benchmark evidence needs a durable repository artifact.
- Update Notion Current State / Known Bugs / Decisions / Session History after verified completion.

**Interfaces:**
- Consumes every prior task.
- Produces one merge-ready Performance V2 PR and one consolidated `main` APK for physical smoke.

- [ ] **Step 1: Run post-optimization benchmark CI**
  - Same 1k/5k/10k workloads and revision label as baseline.
  - Compare SQL counts and elapsed scaling.
  - Record cold vs unchanged/no-op refresh behavior.

- [ ] **Step 2: Run full CI v2.1**
  - Required green: Format, Domain, Data, App, Core Common, SQLDelight migration verification, Supabase, Release Compile, CI Gate.
  - Compile matrix / Native Package may remain skipped only when the normal full planner says so.

- [ ] **Step 3: Independent diff review**
  - Review specifically for canonical identity weakening, stale-write races, cache invalidation omissions, cancellation leaks, unbounded memory and migration compatibility.
  - Fix findings and rerun the affected CI plus full CI if production code changes.

- [ ] **Step 4: Merge only after green**
  - Verify open PR set and dependency order.
  - Merge Performance V2 to `main`.
  - Confirm `main` contains the complete intended state.

- [ ] **Step 5: Trigger APK from `main`**
  - Use the established tree-identical `[apk]` commit convention.
  - Never build the acceptance APK from the feature branch.

- [ ] **Step 6: Physical smoke checklist**
  - Cold Search with all metadata/rating integrations enabled.
  - Repeat identical Search and query churn.
  - Cold open of a previously unseen title.
  - Warm reopen of the same title.
  - Explicit refresh of the same title.
  - Title with thousands of multi-source evidence rows.
  - Chapter source switch and Reader open.
  - Unsafe/provisional chapter cases remain fail-closed.
  - Compare diagnostic stage timings to the pre-optimization APK.

## Self-Review

- Spec coverage: every Performance V2 design section maps to a task above; selective prefetch is explicitly conditional on benchmark evidence.
- Type consistency: freshness/fingerprint persistence belongs to chapter-refresh storage; metadata snapshots are separate and keyed by provider/config fingerprint; rating cache is shared by Search/Discovery/Detail.
- Review Focus coverage: config invalidation (Tasks 2/5/6), stale completions (Tasks 2/5), partial failures (Task 5), large duplicates (Tasks 1/3), query churn (Task 7).
- Proportion: plan defines public seams, tests and task order; implementation bodies remain with executors.
- Project rules: all verification uses GitHub Actions; no local Gradle; APK only from consolidated `main`.
