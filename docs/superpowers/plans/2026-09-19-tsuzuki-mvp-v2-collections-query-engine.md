# Tsuzuki MVP-V2 Collections Query Engine — CI-First Agent Execution Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the Tsuzuki Collections Engine (Milestone 8) delivering a provider-neutral query AST, deterministic normalization, provider capabilities compiler and query planner, residual pagination evaluator, collection/folder/list persistence, persistent query cache with in-flight deduplication, lazy priority scheduler, system/user definition repositories, and unified list execution entry point.

**Architecture:** Collections provide declarative discovery and catalog organization (`Collection -> Folder -> List`). Each List targets a primary catalog provider and evaluates a declarative, provider-neutral query expression (`ALL`, `ANY`, `NOT`, `Predicate`). Query execution separates provider-pushable predicates from residual in-memory evaluation, preserves strict pagination boundaries across partial provider filters, and executes with viewport-driven lazy priority scheduling (`VISIBLE`, `NEXT_SCREEN`, `BACKGROUND`).

**Tech Stack:** Kotlin, Coroutines/Flow, SQLDelight, JUnit 5, Kotest, Gradle, Mihon/Tsuzuki Architecture.

**Spec References:** `docs/TSUZUKI-SPEC.md`
- Section 2: Core architectural invariants (`Metadata != Source`, `CatalogProvider != Reading Source`, `Canonical identity != Provider identity`)
- Section 12: Collections Engine (12.1 Structure, 12.2 Declarative List model, 12.3 Provider-neutral query AST, 12.4 Query Planner, 12.5 Residual pagination, 12.6 Scheduler, 12.7 System and user collections, 12.8 Portable format)
- Section 28: Technical roadmap (Milestone 8: Collections Query Engine)

**Milestone Scope Boundary:**
- **In Scope (Milestone 8):** Query AST, normalization, provider capabilities, planner, residual evaluator, pagination, collection/folder/list persistence, query cache, in-flight deduplication, lazy priority scheduler, system/user definitions, execution pipeline.
- **Out of Scope (Milestone 9):** Full Compose Collections UI, browse screen integration, visual list drag-and-drop reordering, interactive query builder UI, and external JSON file import/export file pickers. All UI and import/export tooling remains strictly out of scope until Milestone 8 acceptance.

---

## Complete Milestone 8 Decomposition

### Block A: Query AST + Deterministic Normalization
- Provider-neutral AST: `QueryExpression` (`All`, `Any`, `Not`, `Predicate`), `QueryField`, `QueryOperator`, `QueryValue`.
- Strict validation taxonomy (`QueryValidationException`, `QueryValidationResult`).
- Deterministic normalizer (`QueryNormalizer`): flattening nested same-type operators, commutative child sorting, exact duplicate elimination, sorted set normalization for `IN`, and canonical identity generation (`normalizedKey()`).

### Block B: Provider Capabilities + Compiler + Planner
- `CatalogProviderCapabilities`: feature flags, pushable fields, pushable operators, filter value mapping constraints.
- `QueryCompiler`: translates pushable AST subsets into provider-specific query arguments/payloads.
- `QueryPlanner`: splits normalized queries into `pushedExpression` (handled remotely) and `residualExpression` (evaluated locally).

### Block C: Residual Evaluator + Correct Pagination
- `ResidualEvaluator`: evaluates non-pushed AST predicates against returned catalog items or canonical titles.
- `ResidualPaginator`: repeatedly fetches upstream pages if residual local filtering eliminates items, maintaining stable page sizes without breaking pagination or dropping valid results.

### Block D: Collection / Folder / List Persistence
- Persistence entities and SQLDelight schema for `tsuzuki_collections`, `tsuzuki_folders`, and `tsuzuki_lists`.
- Declarative models: IDs, parent IDs, titles, sort orders, layout types, provider IDs, serialized query ASTs, enabled flags, timestamps, and schema versions.

### Block E: Persistent Cache + In-Flight Deduplication
- Cache key derived deterministically from provider ID, normalized query key, sort configuration, and page index.
- Memory and disk caching with configurable time-to-live (TTL) and stale-while-revalidate semantics.
- Concurrent query deduplication (`Mutex` / in-flight `Deferred` sharing) to prevent duplicate simultaneous provider queries.

### Block F: Lazy Priority Scheduler
- Priority queues: `VISIBLE` (immediate / high concurrency), `NEXT_SCREEN` (prefetch / medium concurrency), `BACKGROUND` (idle / low concurrency).
- Viewport visibility signals, request cancellation on scroll away, and bounded provider concurrency per host.

### Block G: System / User Definitions + Repositories
- System-defined collections (e.g. Popular, Seasonal, Trending, Highest Rated) flagged with `origin = SYSTEM`.
- User-authored collections flagged with `origin = USER`.
- Copy-on-write / customization support: duplicating a system collection creates an independent user-owned replica.

### Block H: Integrated List Execution Entry Point
- Facade `CollectionEngine` or `ExecuteListUseCase` bringing together Scheduler, Cache, Planner, Provider, and Residual Evaluator into a clean reactive Flow/paging stream.

---

## Detailed Task Breakdown

### Task 1: Block A — Provider-Neutral Query AST & Validation (Current)
- [x] Create package `eu.kanade.tachiyomi.data.collections.query`.
- [x] Implement `QueryExpression`: `All`, `Any`, `Not`, `Predicate`.
- [x] Implement `QueryField`: generic domain fields without provider-specific types.
- [x] Implement `QueryOperator`: `EQUALS`, `NOT_EQUALS`, `IN`, `GREATER_THAN`, `GREATER_OR_EQUAL`, `LESS_THAN`, `LESS_OR_EQUAL`, `BETWEEN`, `CONTAINS`.
- [x] Implement `QueryValue`: `StringValue`, `IntegerValue`, `DoubleValue`, `BooleanValue`, `ListValue`, `RangeValue`, `RelativeTemporal`.
- [x] Implement validation taxonomy: `QueryValidationException`, `QueryValidationResult`, and comprehensive semantic validation rules.

### Task 2: Block A — Deterministic Normalization & Canonical Key
- [x] Implement `QueryNormalizer`:
  - Flatten nested `All` within `All` and `Any` within `Any`.
  - Sort commutative children deterministically.
  - Eliminate duplicate expressions.
  - Sort and deduplicate `IN` list values.
  - Recursively normalize through `Not`.
  - Conservative normalization: avoid De Morgan or distributive predicate distortion.
  - Produce stable canonical key representation (`normalizedKey()`).

### Task 3: Block A — Unit Tests & Validation
- [x] Implement `app/src/test/java/eu/kanade/tachiyomi/data/collections/QueryNormalizerTest.kt`.
- [x] Verify all 12 core test invariants:
  1. Provider-neutral AST construction;
  2. Nested `All` flattening;
  3. Nested `Any` flattening;
  4. Commutative child ordering invariance;
  5. Duplicate predicate elimination;
  6. `IN` value sorting and deduplication;
  7. Recursive `Not` normalization;
  8. Rejection of invalid operator/value pairs;
  9. No provider-specific types in query domain;
  10. Normalization idempotence;
  11. Non-mutation of original AST structures;
  12. Collision resistance for non-equivalent query structures.

### Task 4: Subsequent Blocks (B through H)
- [ ] Block B: Provider capabilities & planner.
- [ ] Block C: Residual filtering & pagination.
- [ ] Block D: SQLDelight persistence for collections/folders/lists.
- [ ] Block E: Query result cache & in-flight deduplication.
- [ ] Block F: Lazy priority scheduler.
- [ ] Block G: System & user repositories.
- [ ] Block H: Integrated list execution pipeline.
