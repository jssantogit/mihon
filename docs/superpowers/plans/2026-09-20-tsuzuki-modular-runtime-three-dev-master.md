# Tsuzuki Modular Runtime — Three-Developer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved modular Tsuzuki architecture with three coordinated developers while preserving a working canonical Library/Reader at every integration checkpoint.

**Architecture:** Upgrade CI before product work, establish one shared contract/schema baseline, then run three largely non-overlapping workstreams: Dev A owns Integrations, canonical search and chapter evidence; Dev B owns Add-ons, content resolution, Reader/download delivery; Dev C owns optional Supabase account/sync, Home/Collections, navigation shell and final integration. Cross-stream interaction happens only through contracts frozen in Wave 0 and explicit merge checkpoints.

**Tech Stack:** Kotlin, Jetpack Compose, Metro DI, SQLDelight, OkHttp/Ktor where already used, Mihon extension runtime, Supabase Auth/PostgREST/RPC, GitHub Actions CI v2.1.

**Spec:** `docs/superpowers/specs/2026-09-20-tsuzuki-modular-runtime-architecture-design.md`

## Global Constraints

- Baseline design is approved at `docs/superpowers/specs/2026-09-20-tsuzuki-modular-runtime-architecture-design.md`.
- Tsuzuki canonical identity must never become Kitsu, MAL, Mihon Source, Add-on, or display-title identity.
- `chapterCount` is metadata only and must never synthesize chapter rows.
- Integrations and Add-ons have separate lifecycle/trust systems even when they satisfy compatible capabilities.
- Fresh install has no enabled Integration, no installed/configured Home discovery source, and no implicit Kitsu fallback.
- Supabase account is optional; local Library/Reader/downloads/Add-ons/Collections must work without login.
- Google login is not supported by the target product.
- Google Drive cloud state is not migrated; local SQLDelight state is the bootstrap source for optional Supabase sync.
- Local SQLDelight mutations succeed before cloud sync.
- Executable Add-ons are never silently installed or trusted from synced state.
- Automatic content fallback is global opt-in and disabled by default.
- Preferred content provider is per canonical title.
- CI v2.1 affected mode is the normal task gate; request `[ci-full]` at integration checkpoints and `[apk]` only for device-visible checkpoints.
- Wave -1 CI v2.1 must be merged before the runtime-v2 foundation branch is created.
- Follow `.agents/rules/tsuzuki-development.md`: small commits, diff review, at most two correction cycles per task.

## Review Focus

- **Concurrent canonical materialization:** two providers/devices describing the same externally verified work must converge without title-string merging. Dev A Task A4 owns the regression tests.
- **Provider disappearance:** disabling an Integration/Add-on must not delete canonical titles, chapters, progress, or downloaded artifacts. Dev A Task A5 and Dev B Task B6 own the tests.
- **Retry ambiguity:** replaying one Supabase mutation after timeout must be idempotent and must not advance cursor twice. Dev C Task C3 owns the tests.
- **Executable restore:** synced Add-on state must not install/trust an APK automatically on a fresh device. Dev C Task C4 and Dev B Task B1 own the tests.
- **Read-path continuity:** changing provider preference or losing the preferred provider must never reset canonical reading progress. Dev B Task B4/B5 own the tests.

---

## 1. Branch and ownership strategy

The implementation uses a CI-preparation wave, one short serial foundation wave, then parallel branches.

### Wave -1 — CI v2.1

Complete and merge `tsuzuki/ci-v2-1` before any runtime-v2 implementation branch is created.

Acceptance:
- Tsuzuki-only Domain/Data/App changes select filtered Tsuzuki tests rather than whole-module suites;
- downstream consumers are compile-checked without replaying unrelated test suites;
- SQLDelight changes run migration verification;
- `supabase/**` changes run the Supabase backend lane without a full Gradle suite;
- torrent/native paths run a package gate;
- `[ci-full]` is the only automatic full-verification token and includes release compilation;
- legacy `[full-ci]` is retired;
- runtime-v2 Dev A/B/C/integration/torrent branches have APK lanes;
- planner behavior is regression-tested.

Only after this wave is green and merged should Wave 0 begin.

### Wave 0 — serial foundation

Dev A creates and merges the common contract/schema foundation first.

Branch:

~~~bash
git switch tsuzuki/bootstrap
git pull --ff-only
git switch -c tsuzuki/runtime-v2-foundation
~~~

After Foundation Task F1 is green and merged, record the foundation SHA. All three implementation branches are created from that exact SHA:

~~~bash
git switch tsuzuki/bootstrap
git pull --ff-only
git switch -c tsuzuki/runtime-v2-dev-a

git switch tsuzuki/bootstrap
git switch -c tsuzuki/runtime-v2-dev-b

git switch tsuzuki/bootstrap
git switch -c tsuzuki/runtime-v2-dev-c
~~~

Do not create the three branches before Wave 0 is merged.

### Permanent file ownership during parallel work

| Area | Owner | Other developers |
| --- | --- | --- |
| `domain/.../tsuzuki/integration/**` | Dev A | read-only |
| `data/.../tsuzuki/integration/**` | Dev A | read-only |
| Kitsu/MAL Integration adapters | Dev A | read-only |
| canonical search/detail UI | Dev A | read-only |
| `chapter/evidence/**`, chapter authority | Dev A | read-only |
| `domain/.../tsuzuki/addon/**` | Dev B | read-only |
| `domain/.../tsuzuki/content/**` | Dev B | read-only |
| Mihon Add-on adapter | Dev B | read-only |
| content selector / Reader integration | Dev B | read-only |
| canonical downloads/local/torrent delivery | Dev B | read-only |
| `domain/.../tsuzuki/account/**` | Dev C | read-only |
| Supabase client/backend/sync | Dev C | read-only |
| Home/Collections composition | Dev C | read-only |
| `HomeScreen.kt`, Settings main shell | Dev C | read-only |
| old Google auth / Drive removal | Dev C | read-only |

Shared hot files such as `HomeScreen.kt`, `SettingsMainScreen.kt`, top-level Gradle dependency files, and the final navigation wiring have one owner only: Dev C.

Reader files have one owner only: Dev B.

Catalog/search/chapter-authority files have one owner only: Dev A.

---

## 2. Shared interface freeze from Wave 0

All workstreams code against these contracts after the foundation merge.

~~~kotlin
@JvmInline
value class IntegrationId(val value: String)

@JvmInline
value class AddonId(val value: String)

interface SearchProvider {
    val integrationId: IntegrationId
    suspend fun search(query: CatalogQuery): Result<CatalogPage>
}

interface DiscoveryProvider {
    val integrationId: IntegrationId
    suspend fun trending(offset: Int, limit: Int): Result<CatalogPage>
    suspend fun popular(offset: Int, limit: Int): Result<CatalogPage>
    suspend fun recentlyUpdated(offset: Int, limit: Int): Result<CatalogPage>
}

interface MetadataProvider {
    val integrationId: IntegrationId
    suspend fun getDetails(externalId: String): Result<CatalogItem>
}

interface ChapterEvidenceProvider {
    val producerId: String
    suspend fun evidenceFor(canonicalTitleId: String): Result<List<ChapterEvidence>>
}

interface RatingsProvider {
    val integrationId: IntegrationId
    suspend fun ratings(externalId: String): Result<List<ExternalRating>>
}

interface TrackingProvider {
    val integrationId: IntegrationId
    suspend fun isConnected(): Boolean
    suspend fun update(update: TrackingUpdate): Result<Unit>
}

interface ContentProvider {
    val addonId: AddonId
    suspend fun resolve(
        canonicalTitleId: String,
        canonicalChapterId: String,
    ): Result<List<ContentOption>>
}

interface ChapterProbeProvider {
    val addonId: AddonId
    suspend fun probe(canonicalTitleId: String): Result<List<ChapterEvidence>>
}
~~~

Registry contracts:

~~~kotlin
interface IntegrationRegistry {
    fun searchProviders(): List<SearchProvider>
    fun discoveryProviders(): List<DiscoveryProvider>
    fun metadataProviders(): List<MetadataProvider>
    fun chapterEvidenceProviders(): List<ChapterEvidenceProvider>
    fun ratingsProviders(): List<RatingsProvider>
    fun trackingProviders(): List<TrackingProvider>
}

interface AddonRegistry {
    fun contentProviders(): List<ContentProvider>
    fun chapterProbeProviders(): List<ChapterProbeProvider>
}
~~~

The foundation also freezes these cross-stream domain types:

~~~kotlin
enum class ChapterEvidenceAuthority { EDITORIAL, ADDON_PROVISIONAL }
enum class CanonicalChapterConfirmation { CONFIRMED, PROVISIONAL, CONFLICTED }

data class ExternalRating(
    val providerId: String,
    val label: String,
    val value: Double,
    val scaleMax: Double,
)

data class TrackingUpdate(
    val externalId: String,
    val chapterProgress: Double?,
    val status: LibraryStatus?,
    val score: Double?,
)

data class ChapterEvidence(
    val id: String,
    val canonicalTitleId: String,
    val producerKind: ProducerKind,
    val producerId: String,
    val externalChapterKey: String?,
    val rawLabel: String,
    val rawNumber: Double?,
    val volume: Int?,
    val title: String?,
    val observedAt: Long,
    val confidence: Double,
    val authority: ChapterEvidenceAuthority,
)

data class ContentOption(
    val key: String,
    val canonicalChapterId: String,
    val addonId: AddonId,
    val language: String?,
    val scanlationGroup: String?,
    val releaseDate: Long?,
    val delivery: ContentDelivery,
)
~~~

Later tasks may add optional fields additively. They must not rename/remove these fields while another workstream is active.

---

## 3. Database migration rule

Parallel SQLDelight migration-number conflicts are prohibited.

Wave 0 owns **migration `30.sqm`** and creates all cross-stream tables required for parallel implementation:

- integration settings;
- chapter evidence;
- content bindings;
- per-title preferred Add-on;
- chapter-new/acknowledgment state;
- Continue Reading suppression state;
- canonical download artifacts;
- Supabase delta cursors;
- pending Supabase mutation batches.

After Wave 0, no developer adds another migration until the first parallel merge checkpoint.

If a workstream discovers a schema omission:

1. add the desired schema change to its `.sq` definition only behind a branch-local failing migration test;
2. stop and report the required migration delta in the handoff;
3. the integration steward assigns the next migration number after merge order is known.

This prevents three branches from independently creating `31.sqm`.

---

## 4. Workstream decomposition

### Dev A — Integrations, Search, canonical chapter authority

Plan: `docs/superpowers/plans/2026-09-20-tsuzuki-modular-runtime-dev-a.md`

Deliverables:

- Integration settings/registry implementation;
- Kitsu as an explicit Integration;
- MAL metadata/search/ratings/tracking adapter boundary;
- multi-Integration Search aggregation and identity reconciliation;
- ChapterEvidence persistence/reconciliation;
- confirmed/provisional/conflicted chapter state;
- integration-driven update observation;
- Search and canonical detail surfaces;
- Integration Settings screen.

Dev A must not modify Reader/download/add-on runtime or Supabase sync.

### Dev B — Add-ons, content resolver, Reader/download delivery

Plan: `docs/superpowers/plans/2026-09-20-tsuzuki-modular-runtime-dev-b.md`

Deliverables:

- AddonRegistry implementation;
- Mihon extension package → one visible Add-on adapter;
- ContentBinding discovery/cache;
- ContentProvider and ChapterProbeProvider implementation;
- per-title preferred Add-on;
- deterministic ContentOption ranking;
- selector/fallback behavior;
- Reader “Change source”;
- provider-neutral PreparedChapterContent seam;
- canonical downloads;
- Local Content adapter;
- remote manifest/torrent delivery foundation.

Dev B must not modify Integration/Kitsu/MAL logic, Home shell, or Supabase sync.

### Dev C — Supabase, optional account, Home/Collections, shell/integration

Plan: `docs/superpowers/plans/2026-09-20-tsuzuki-modular-runtime-dev-c.md`

Deliverables:

- optional Supabase e-mail/password account;
- Postgres/RPC/RLS sync backend;
- idempotent local-first Supabase sync;
- retirement of Google auth and Drive target path;
- empty-by-default Home;
- Continue Reading automatic system row;
- capability-driven Collections/Home;
- final Home/Search/Library/Settings navigation;
- Settings shell and Account screen;
- final integration of Dev A/B settings destinations;
- final full CI and device smoke.

Dev C must not implement canonical search logic, content resolver logic, or Reader behavior.

---

## 5. Wave schedule

### Wave 0 — Contract/schema foundation

**Owner:** Dev A  
**Parallelism:** none  
**Gate:** CI v2 full

- [ ] Dev A completes Foundation Task F1 from the Dev A plan.
- [ ] Run CI v2 full because migration 30 and cross-module domain contracts change.
- [ ] Human reviews the contract names and file ownership map.
- [ ] Merge `tsuzuki/runtime-v2-foundation` into `tsuzuki/bootstrap`.
- [ ] Record the merge SHA and create Dev A/B/C branches from it.

No product UI should change in Wave 0.

### Wave 1 — Independent engines

**Dev A:** registry + Kitsu/MAL adapters + aggregated search engine.  
**Dev B:** Add-on registry + Mihon adapter + ContentBinding/ContentProvider.  
**Dev C:** Supabase Auth/backend + sync client foundation.

These tasks have no UI-shell dependency and should run concurrently.

Each accepted task:
1. ends in a small commit;
2. pushes the branch;
3. waits for CI v2;
4. posts the standard evidence block from `.agents/rules/tsuzuki-development.md`.

### Wave 2 — Canonical behavior

**Dev A:** ChapterEvidence reconciliation + update observation + canonical detail models.  
**Dev B:** Content resolver + preference/fallback + Reader selector.  
**Dev C:** migrate sync adapters to Supabase + Home composition engine.

Continue in parallel.

The cross-stream handoff contracts are:

~~~text
Dev A produces CanonicalChapter + evidence state.
Dev B consumes CanonicalChapter IDs and may submit ADDON_PROVISIONAL evidence.
Dev C consumes canonical Library/progress/update repositories; it never constructs chapter/source identity.
~~~

### Wave 3 — User surfaces

**Dev A:** Search tab/detail/Integrations settings.  
**Dev B:** Add-ons settings/content selector/download/local delivery.  
**Dev C:** Home/Collections/Account/Settings shell.

Do not modify `HomeScreen.kt` outside Dev C.

Do not add A/B destinations to the primary navigation in their branches. Their screens remain directly instantiable Compose/Voyager screens until final wiring.

### Merge checkpoint 1

Create an integration branch from current bootstrap:

~~~bash
git switch tsuzuki/bootstrap
git pull --ff-only
git switch -c tsuzuki/runtime-v2-integration
~~~

Merge in this order:

~~~bash
git merge --no-ff tsuzuki/runtime-v2-dev-a
git merge --no-ff tsuzuki/runtime-v2-dev-b
~~~

Run CI v2 full.

If A+B fail to integrate, fix only interface/type mismatch on the integration branch. Do not begin UI-shell wiring until A+B are green together.

### Wave 4 — Dev C integration steward pass

Dev C updates from the A+B integration branch, then completes shared-shell wiring:

~~~bash
git switch tsuzuki/runtime-v2-dev-c
git merge tsuzuki/runtime-v2-integration
~~~

Dev C then owns:

- `HomeScreen.kt` target tabs;
- `SettingsMainScreen.kt` links to A/B/C Settings destinations;
- removal of Updates/History/Browse from target nav;
- retirement of Google/Drive target screens/jobs;
- fresh-install empty-state smoke.

Run full CI and request APK.

### Wave 5 — Production torrent delivery

After Dev C’s integrated four-tab runtime is green on device, freeze shared-shell/Gradle changes and create a final delivery branch from that integrated head:

~~~bash
git switch tsuzuki/runtime-v2-dev-c
git switch -c tsuzuki/runtime-v2-torrent
~~~

Dev B executes the production torrent task from the Dev B plan against this integrated baseline. This phase is serial with respect to top-level Gradle files because the torrent runtime adds native dependencies.

The planned production engine is FrostWire jlibtorrent 2.0.12.9:
- MIT-licensed Java/SWIG wrapper;
- Android artifacts for arm, arm64, x86, x86_64;
- the maintained 2.0.12 line supports Android API 26, matching Tsuzuki’s current minSdk 26;
- the engine remains hidden behind `TorrentArtifactEngine`, so it can be replaced without changing Core/Reader APIs.

Initial torrent behavior:
- acquire only the explicitly selected torrent file/artifact;
- prioritize that file and disable unwanted files;
- respect existing Wi-Fi-only download preference for persistent downloads;
- no persistent background seeding after acquisition completes;
- store temporary reader artifacts in app-private cache and canonical downloads in the canonical download path;
- reuse Archive/Directory/EPUB Reader loaders after acquisition.

Run full CI and an ARM64 device smoke before the final program merge.

---

## 6. Integration acceptance gate

Before merging the program back to `tsuzuki/bootstrap`, the integrated branch must prove:

~~~text
Fresh install:
  no account required
  no Integration enabled
  no Add-on installed
  Home has no discovery rows
  Search shows Integration setup CTA

Configured:
  enable Kitsu
  install content Add-on
  search Dandadan once
  add one canonical Library title
  open canonical detail
  tap chapter
  choose one Add-on option
  read
  reopen next chapter via remembered per-title Add-on

Fallback:
  preferred Add-on unavailable
  auto fallback OFF -> selector
  auto fallback ON -> deterministic alternative

State:
  Continue Reading only after real reading
  +N new chapter state decrements as chapters are read
  downloaded chapter survives Add-on removal

Cloud:
  use app without login
  register/login with Supabase e-mail/password
  sync one Library/progress mutation
  second device receives delta
  repeated push is idempotent
  logout leaves local data usable
~~~

---

## 7. CI policy

Per task, use the affected CI v2 path from the branch push.

At these checkpoints force full verification with commit message token `[ci-full]`:

- Foundation Wave 0;
- A+B integration merge;
- Dev C final shell/Supabase integration;
- final program merge.

Request `[apk]` only for:

- first end-to-end chapter selector → Reader build;
- final Home/Search/Library/Settings + Supabase account build;
- torrent/local-content device smoke if those transports require physical-device verification.

Do not use successful compilation as a substitute for behavior tests.

---

## 8. Handoff format between developers

Every cross-dev handoff must contain:

~~~text
HANDOFF — DEV
BASE_SHA:
HEAD_SHA:
TASKS_COMPLETED:
PUBLIC_INTERFACES_CHANGED:
MIGRATIONS:
CI_RUN:
CI_RESULT:
FILES_OTHER_DEVS_MAY_NOW_CONSUME:
KNOWN_LIMITATIONS:
NEXT_DEPENDENCY:
~~~

If `PUBLIC_INTERFACES_CHANGED` is non-empty after Wave 0, the developer must explain why the additive change was necessary. Breaking changes require stopping the other workstreams until the new contract is merged.

---

## 9. Program-level stop conditions

Stop integration and report BLOCKED instead of patching around these failures:

- any implementation requires title-string auto-merge to work;
- any content provider needs to become CanonicalTitle identity;
- `chapterCount` must be expanded into synthetic chapters for a common flow;
- cloud availability becomes required for normal local reading;
- synced Add-on state causes executable installation/trust without user action;
- a new transport requires every content option to fake a Mihon Source;
- concurrent sync conflict can only be “solved” by blind last-write-wins;
- one developer must repeatedly edit another developer’s owned hot files.

These are signs the implementation is violating the approved design rather than merely needing bug fixes.

---

## 10. Program completion

After all three plans are complete:

- [ ] Merge Dev A then Dev B into the integration branch.
- [ ] Merge the A+B integration branch into Dev C.
- [ ] Complete the Dev C integration-steward task and core device smoke.
- [ ] Branch `tsuzuki/runtime-v2-torrent` from the integrated Dev C head.
- [ ] Complete Dev B production torrent delivery and torrent device smoke.
- [ ] Run CI v2 full on the final integrated/torrent head.
- [ ] Build one ARM64 release APK.
- [ ] Execute the full human smoke matrix from Section 6 plus torrent acquisition/read.
- [ ] Review the full diff against the design spec.
- [ ] Remove only dead legacy routes proven unused by the target shell; do not perform unrelated cleanup.
- [ ] Merge the verified final branch into `tsuzuki/bootstrap`.
