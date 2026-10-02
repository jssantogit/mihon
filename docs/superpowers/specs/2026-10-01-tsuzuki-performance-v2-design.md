# Tsuzuki Performance V2 — Design

## Goal

Make Search/Discovery and canonical Detail feel fast even with all metadata/rating integrations and multiple reading add-ons enabled, without weakening canonical identity, fail-closed chapter evidence rules, Reader correctness, or provider/source independence.

The target execution model is:

- **Known/fresh work:** local snapshot -> ready.
- **Known/stale work:** local snapshot -> background revalidation -> delta update.
- **New work:** publish the first safe usable state -> progressively enrich/reconcile remaining providers.

## Evidence behind the redesign

Physical smoke and diagnostics show two distinct bottlenecks:

- Integration metadata/rating resolution commonly adds roughly 0.9–1.5s per title.
- Canonical chapter refresh can process thousands of evidence rows: observed workloads include 3,576 observations at ~8s and 10,647 observations at ~12.8s.
- Existing reconciliation benchmarks cover only 100/500/1,000 observations.
- Canonical Detail already renders local state first, but automatically starts a full chapter refresh and later resolves integration metadata again.
- The chapter inventory cache already provides a 2-minute in-memory TTL plus single-flight, but unchanged cached inventories are still parsed/projected/reconciled again.
- Detail currently loads full persisted evidence rows to derive lightweight UI facts such as mapped-support membership and add-on coverage.
- Reported chapter-count refresh and canonical metadata resolution can both call the same editorial providers during one Detail refresh.
- Search/Discovery publish base content before enrichment, but rating enrichment still fans out over items/providers without a dedicated shared cache/single-flight layer equivalent to the one used for chapter inventory.

Nuvio's current architecture demonstrates useful scheduling patterns: base-first rendering, request-state reuse, in-flight request coalescing, HTTP caching, provider-specific metadata caches, and batching where backend APIs support it. Tsuzuki will borrow those execution patterns, not Nuvio's simpler episode identity model.

## Invariants

Performance work must preserve all current Tsuzuki domain guarantees:

- Canonical work identity is independent of provider/source.
- Reading source selection is independent of metadata source.
- Unsafe or ambiguous chapter evidence remains fail-closed.
- Regular chapter 0 backed only by unsafe provisional evidence still requires independent corroboration.
- Editorial chapter counts never become reading inventory.
- Existing stable chapter/user-state IDs must not change because of an optimization.
- A stale inventory must never overwrite newer evidence.
- Explicit user refresh bypasses freshness shortcuts.
- Provider-native ratings remain visible in their native scales; Tsuzuki Rating remains an aggregate layer.
- No optimization may silently broaden identity matching rules.

## Architecture

### 1. Performance observability and realistic benchmarks

Add stage-level timings/counters for:

- Search base fetch.
- Search rating-identity resolution.
- Search rating fetch/enrichment.
- Detail local projection.
- Detail metadata refresh.
- Chapter inventory fetch/cache replay.
- Inventory -> evidence projection.
- Evidence reconciliation.
- SQL persistence.
- Final Detail projection.

Extend chapter benchmarks to at least 1k/5k/10k evidence observations and add a workload shaped like real multi-provider duplicate evidence, not only one observation per canonical chapter.

Performance tests are regression evidence, not device-specific absolute promises. Physical APK smoke remains the final acceptance proof.

### 2. Freshness policy

Introduce a persisted freshness state for expensive canonical refresh work.

A normal Detail open:

1. renders persisted local state immediately;
2. checks whether chapter and metadata snapshots are fresh;
3. skips network/reconciliation when fresh;
4. revalidates stale data in background.

Explicit **Atualizar** always forces provider revalidation.

Freshness is scoped to the inputs that can invalidate a result, including enabled providers/add-ons and relevant integration configuration.

### 3. Inventory fingerprints and no-op fast path

Persist a stable fingerprint for each successfully reconciled binding/source inventory.

The fingerprint must be based only on chapter identity-relevant inventory fields and stable source identity. On the next fetch:

- same fingerprint -> do not rebuild evidence, parse labels, reconcile, or rewrite chapter/evidence rows;
- changed fingerprint -> compute/process only changed observations where safe;
- missing prior fingerprint -> use the full safe reconciliation path.

The optimization is an execution shortcut only; it must not alter chapter identity semantics.

### 4. Indexed/delta chapter reconciliation

Restructure reconciliation so repeated lookups are indexed once per refresh:

- evidence by stable ID;
- evidence by producer + external key;
- mapped evidence by canonical chapter;
- evidence by producer/source;
- canonical chapters by parsed identity + volume;
- independent support counts where needed for conflict decisions.

Avoid repeated full-list scans from inside the per-observation loop.

Parser output should be reused within the refresh rather than recomputed for the same raw identity inputs.

When inventory change detection provides a trustworthy delta, reconcile only those changed observations plus the minimal surrounding evidence needed to evaluate safety/conflicts.

### 5. SQL persistence fast path

Batch persistence must avoid point queries that are already answerable from a title-level snapshot.

Add repository operations/queries for:

- lightweight evidence projections needed by Detail;
- bulk key lookup when a global uniqueness check is required;
- aggregate add-on coverage;
- mapped canonical chapter IDs;
- binding/source freshness/fingerprint state.

Keep database uniqueness constraints authoritative. Optimization must not trade correctness for fewer queries.

### 6. Cheap Detail projection

Opening an existing title must not hydrate every persisted evidence row.

The UI projection should query only:

- canonical chapters;
- reading progress;
- canonical download state;
- lightweight support/coverage aggregates;
- persisted metadata snapshot;
- artwork snapshot.

Legacy per-chapter download checks must not run across the entire chapter list during ordinary Detail rendering. They may be invoked only where compatibility state is actually unresolved.

### 7. Metadata snapshot + stale-while-revalidate

Persist resolved canonical metadata separately from provider raw state, keyed by canonical title and a configuration/provider fingerprint.

A normal read:

- returns the last resolved snapshot immediately;
- refreshes stale fields in background;
- merges only identity-safe provider results;
- preserves old usable fields when an optional provider fails.

Use in-flight deduplication so simultaneous requests for the same title/config share one resolution.

### 8. Eliminate duplicate editorial provider work

During one refresh cycle, the same provider response should be reusable for both:

- canonical metadata fields; and
- editorial reported chapter count.

Where a provider API exposes those values in one details payload, fetch once and project both outputs.

### 9. Rating identity/result cache

Cache identity-safe rating resolution results by canonical work/provider/config.

Support:

- positive resolved identity/result cache;
- short-lived negative cache for unambiguous misses;
- in-flight single-flight by work/provider;
- invalidation when provider enablement/account/config changes.

Existing strict matching criteria remain unchanged.

### 10. Search/Discovery priority and cancellation

Search/Discovery continue to publish base catalog content immediately.

Enrichment scheduling should:

- prioritize the first/visible items;
- share work for the same canonical/provider identity across Trending, Popular and query results;
- cancel obsolete enrichment when the query changes;
- reuse cached identities/ratings before network calls;
- limit independent network, parsing and persistence concurrency separately.

The domain API may still expose a fully enriched path for callers that explicitly require one.

### 11. Provider-specific batching

Batch only where a provider/API truly supports it.

Do not emulate batching by serially packing unrelated single-item requests. Where batching is unavailable, use cache + single-flight + priority/concurrency control instead.

### 12. Progressive canonical chapters

For a new title, safe chapter evidence may produce an initial visible canonical inventory before every add-on finishes.

Remaining providers continue in background and may add corroboration, variants, or additional chapters.

Safety-critical evidence remains hidden until current corroboration/conflict rules are satisfied. Progressive publication must never make unsafe provisional evidence readable earlier than today.

### 13. Concurrency and backpressure

Use separate bounded concurrency for:

- provider HTTP;
- chapter inventory projection/parsing;
- canonical reconciliation;
- persistence.

Do not solve latency by globally increasing every semaphore. CPU/SQLite work must remain bounded so a large refresh cannot starve UI-facing work.

### 14. Selective prefetch

After the core path is efficient, allow conservative idle-time warming for:

- Continue Reading;
- visible Library items;
- first visible Home/Search cards.

Prefetch must be bounded, cancelable and network/battery-aware. It is not a requirement for correctness or first implementation acceptance.

## Delivery order

Implementation should proceed in independently verifiable waves:

1. Measurement + 1k/5k/10k benchmarks.
2. Chapter no-op/fingerprint + indexed reconciliation/persistence.
3. Detail lightweight projection + freshness.
4. Metadata snapshot/single-flight + duplicate-fetch removal.
5. Rating cache/single-flight + Search scheduling/cancellation.
6. Progressive chapter publication and selective prefetch only after the prior waves are stable.

Each wave must preserve the full existing test suite and add focused regression/performance coverage.

## Acceptance criteria

- Reopening a fresh known title does not perform a full provider refresh/reconciliation.
- An unchanged chapter inventory causes no evidence reconstruction/reconciliation writes.
- Detail local rendering does not load every persisted evidence row.
- Metadata/rating requests for the same title/provider coalesce while in flight.
- Search base results remain visible before optional enrichment.
- Obsolete query enrichment is canceled.
- Explicit refresh still forces provider revalidation.
- Chapter safety/identity regression suites remain green.
- New benchmarks cover 1k/5k/10k evidence workloads.
- Final full CI passes with no local Gradle.
- Physical APK smoke compares cold and warm Search/Detail behavior and confirms no Reader/source regression.

## Non-goals

- Replacing Tsuzuki's canonical chapter model with a provider-centric episode model.
- Relaxing exact/corroborated identity rules to gain speed.
- Background-syncing the entire library continuously.
- Adding new metadata/rating providers during this work.
- UI redesign unrelated to making existing flows faster.
