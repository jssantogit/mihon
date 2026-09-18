# Tsuzuki MVP-V1 Catalog, Kitsu, Search, and Discover — CI-First Agent Execution Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the Tsuzuki CatalogProvider abstraction, Kitsu remote catalog integration, unified title-centric search, and basic Discover feeds, proving that discovery is decoupled from reading sources, remote catalog items remain ephemeral, and canonical identity remains Tsuzuki-owned.

**Architecture:** Tsuzuki introduces a dedicated catalog domain boundary (`tachiyomi.domain.tsuzuki.catalog`) and data boundary (`tachiyomi.data.tsuzuki.kitsu`) above Mihon's infrastructure. Discovery operates through provider-neutral abstractions. Kitsu provides metadata and discovery evidence; it is never a reading source. `CatalogItem` results are ephemeral; user action materializes a `CanonicalTitle` with a Tsuzuki UUID and attaches Kitsu ID as an `ExternalIdentity`. Remote provider failures degrade discovery gracefully without impacting local Library, Reader, downloads, or progress.

**Tech Stack:** Kotlin, Coroutines/Flow, OkHttp (`NetworkHelper`), `kotlinx.serialization`, Metro DI, JUnit 5, Kotest, Gradle, GitHub Actions, AGY/Antigravity, Codex.

**Spec References:** `docs/TSUZUKI-SPEC.md`
- Section 2: Core architectural invariants (`Metadata != Source`, `CatalogProvider != Reading Source`, `Canonical identity != Provider identity`)
- Section 5.1 & 5.2: Canonical identity & `CatalogItem` versus `CanonicalTitle` (ephemeral catalog items)
- Section 6.1–6.5: Catalog architecture, provider abstraction, provider failure, Search, Discover, Scores
- Section 18: Offline behavior and graceful degradation
- Section 23: MVP-V1 canonical reading vertical definition
- Section 28: Technical roadmap (Step 2: CatalogProvider + Kitsu, Step 3: Unified Search / basic Discover)

**Development Workflow:** `docs/TSUZUKI-DEVELOPMENT.md`, `AGENTS.md`, `.agents/rules/tsuzuki-development.md`

**Preceding Foundation:** `docs/superpowers/plans/2026-09-17-tsuzuki-mvp-v1-canonical-foundation-ci-first.md` (already merged into `tsuzuki/bootstrap` via PR #2)

---

## Current branch state

Planning/documentation currently lives on:

```text
tsuzuki/bootstrap
```

Implementation branch to create when execution begins:

```text
tsuzuki/mvp-v1-catalog-kitsu-search-discover
```

Implementation base:

```text
tsuzuki/bootstrap
```

Draft PR:

```text
Not created yet.
Create it only after the implementation branch exists.
Base it on tsuzuki/bootstrap.
Do not assume a PR number in advance.
```

Already present from foundation:

```text
domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalIdentityState.kt
domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalTitle.kt
domain/src/main/java/tachiyomi/domain/tsuzuki/model/ExternalIdentity.kt
domain/src/main/java/tachiyomi/domain/tsuzuki/model/LibraryStatus.kt
domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalLibraryEntry.kt
domain/src/main/java/tachiyomi/domain/tsuzuki/model/SourceMappingAvailability.kt
domain/src/main/java/tachiyomi/domain/tsuzuki/model/SourceTitleMapping.kt
domain/src/main/java/tachiyomi/domain/tsuzuki/repository/CanonicalTitleRepository.kt
domain/src/main/java/tachiyomi/domain/tsuzuki/repository/CanonicalLibraryRepository.kt
domain/src/main/java/tachiyomi/domain/tsuzuki/repository/SourceTitleMappingRepository.kt
domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitle.kt
data/src/main/sqldelight/tachiyomi/data/tsuzuki_titles.sq
data/src/main/sqldelight/tachiyomi/data/tsuzuki_external_identities.sq
data/src/main/sqldelight/tachiyomi/data/tsuzuki_library_entries.sq
data/src/main/sqldelight/tachiyomi/data/tsuzuki_source_mappings.sq
data/src/main/sqldelight/tachiyomi/migrations/15.sqm
data/src/main/java/tachiyomi/data/tsuzuki/CanonicalTitleRepositoryImpl.kt
data/src/main/java/tachiyomi/data/tsuzuki/CanonicalLibraryRepositoryImpl.kt
data/src/main/java/tachiyomi/data/tsuzuki/SourceTitleMappingRepositoryImpl.kt
```

Do not modify existing canonical persistence or library tables unless a task explicitly dictates.

---

## Milestone boundaries

### In scope for this milestone (Phase 2 & Phase 3)

1. **Provider-neutral catalog abstraction:** Domain contracts for `CatalogItem`, `CatalogPage`, `CatalogQuery`, `CatalogScore`, `CatalogSort`, and `CatalogProvider`.
2. **Kitsu edge API client & serialization:** HTTP communication via Mihon's `NetworkHelper`, JSON:API DTOs, and robust HTTP/serialization error taxonomy mapping.
3. **`KitsuCatalogProvider` implementation:** Search, trending manga, and title detail retrieval mapped cleanly to domain entities.
4. **Catalog-to-Canonical materialization:** `MaterializeCanonicalTitleFromCatalog` interactor ensuring user intent creates a `CanonicalTitle` with a Tsuzuki UUID and stores Kitsu ID as an `ExternalIdentity`.
5. **Unified Search & Discover interactors:** `SearchCatalog` and `GetDiscoverFeed` domain use cases supporting graceful degradation.
6. **Metro dependency injection:** Wiring providers, clients, and interactors into `AppScope`.
7. **Comprehensive unit and contract tests:** Mock JSON fixtures for Kitsu edge responses and resilience tests.
8. **Minimal title-centric Search / Discover UI:** A small Compose surface that proves the catalog vertical slice on-device, including loading, success, empty, degraded, and error states plus a provider-neutral item preview.

### Explicit non-goals for this plan

Do **NOT** implement any of the following in this milestone:
- **Canonical Library migration** (Phase 4).
- **Source Resolver heuristics & reading source selection** (Phase 5).
- **Canonical Chapter Engine & ChapterVariant mapping** (Phase 6).
- **Reader / downloader / extension execution changes** (Phase 7).
- **Collections Query AST & Query Planner** (MVP-V2).
- **Google Drive private sync & Google Auth** (MVP-V3).
- **Deep visual redesign or production-polish of Search/Discover:** this milestone includes the minimal Compose UI required to prove the title-centric Search/Discover vertical slice. Broader navigation redesign and visual maturation remain later work.

---

## Google / Gemini / Antigravity prompting contract

This execution plan follows Google and Antigravity prompting standards for reasoning models.

### 1. Keep durable context outside the task prompt

Stable project rules live in:
```text
AGENTS.md
.agents/rules/tsuzuki-development.md
docs/TSUZUKI-SPEC.md
docs/TSUZUKI-DEVELOPMENT.md
this implementation plan
```
Task prompts carry only dynamic task instructions and immediate file paths.

### 2. Use concise, direct instructions

Follow the standard turn progression:
```text
EXPLORE -> PLAN -> EXECUTE -> VERIFY -> STOP
```

### 3. Put repository context before the final task instruction

Load relevant source and test files before giving the concrete directive. Anchor with:
```text
Based on the repository context and constraints above, execute only Task N.
```

### 4. Use explicit prompt components

Handoffs to agents must contain:
```text
<OBJECTIVE_AND_PERSONA>
<CONTEXT>
<CONSTRAINTS>
<INSTRUCTIONS>
<OUTPUT_FORMAT>
<RECAP>
```

### 5. Narrow scope and termination criteria

Every task specifies allowed paths, forbidden paths, acceptance criteria, CI gate, retry limit, and stop conditions. A task terminates immediately once its acceptance criteria are met.

### 6. Rules and hooks enforce invariants

AGY relies on persistent rules in `.agents/rules/tsuzuki-development.md` to protect architectural invariants.

### 7. No chain-of-thought requests

Do not ask workers to expose internal thoughts. Require factual evidence: files changed, commands run, test pass/fail counts, CI run IDs, and diff summaries.

### 8. Bounded investigation

- One consolidated discovery pass per task.
- Batch all related file reads into a single model turn.
- Search is closed once required changes are identified.
- At most two correction cycles after CI failure.
- If two correction cycles fail for different root causes, stop as `BLOCKED`.

### 9. Delta retry only

Retries must state: `FAILED_OR_MISSING`, `NEW_EVIDENCE`, `REQUIRED_CORRECTION`, `PRESERVE`, and `REMAINING_ACCEPTANCE`. Never restart tasks from scratch.

### 10. CI evidence outranks agent confidence

Acceptance hierarchy:
```text
Focused local test (optional)
       ↓
Fast CI (mandatory task gate)
       ↓
APK checkpoint (only for final visible UI/device validation)
```

`Full Verify` is not a default milestone gate for this block. Use it only if a distinct release-integration risk is discovered that is not already covered by the final APK build.

---

## AGY / Codex role contract

### AGY orchestrator

- Reads specification, rules, and implementation plan.
- Classifies scope and enforces boundaries.
- Delegates implementation to Codex worker.
- Reviews git diff against spec invariants.
- Waits for Fast CI results.
- Issues delta retries or marks tasks as accepted.

### Codex worker

- Edits code strictly within allowed paths.
- Writes red-to-green tests closest to the behavior being modified.
- Applies minimal mechanical fixes indicated by CI compiler/linter diagnostics.
- Returns a compact evidence packet.

### Fail-closed state machine

```text
INTAKE -> EXPLORE -> PLANNED -> EXECUTING -> CI_WAIT -> ACCEPTANCE -> DONE
                                                        |
                                                        +-> EXECUTING (delta retry, max 2)
                                                        +-> BLOCKED (escalate to user)
```

---

## Global architectural constraints & failure modes

1. **`Metadata != Source`:** Kitsu is a metadata catalog provider. It has no chapters, pages, or reader URLs. It must never implement or mimic a Mihon reading source.
2. **`Source Manga != CanonicalTitle`:** Mihon's `Manga` model remains operational source infrastructure. `CanonicalTitle` is Tsuzuki's identity.
3. **`CatalogProvider != Reading Source`:** The `CatalogProvider` interface provides remote discovery and title enrichment only.
4. **`Canonical identity != Provider identity`:** Kitsu ID is an `ExternalIdentity` record (`provider = "kitsu", externalId = "1234"`). The `CanonicalTitle.id` is ALWAYS a Tsuzuki-generated UUID.
5. **Ephemeral Remote Catalog Results:** Appearing in Search, Discover, or Trending MUST NOT insert records into `tsuzuki_titles` or `tsuzuki_library_entries`. Remote items remain ephemeral `CatalogItem` domain models until an explicit user action (e.g. Add to Library, Open Details, Materialize) triggers persistence.
6. **Graceful Degradation:** A Kitsu API outage, rate limit (HTTP 429), or network drop must NEVER break or block:
   - Local Library browsing.
   - Reader operation.
   - Download queue execution.
   - Local progress updates.
   When Kitsu fails, Search and Discover report a typed `CatalogError` allowing the UI to present offline or empty states gracefully.
7. **Scores Remain Provider-Specific:** Kitsu scores (0–100%) remain associated with the provider (`CatalogScore(provider = "kitsu", value = 84.5, maxValue = 100.0)`). The system must never calculate a synthetic universal average.
8. **Upstream Compatibility:** Do not modify upstream Mihon networking or reader code. Use `NetworkHelper` and standard OkHttp clients.

---

## Fast CI verification contract

GitHub Actions (`.github/workflows/ci.yml`) runs four parallel jobs on every push to `tsuzuki/**`:

1. **Format:** `./gradlew spotlessCheck`
2. **Kotlin Compile:** `./gradlew :app:compileDebugKotlin`
3. **Unit Tests:** `./gradlew testDebugUnitTest`
4. **SQLDelight Migrations:** `./gradlew verifySqlDelightMigration`

Acceptance for any task requires all relevant jobs to be green.

---

## File structure produced by this plan

### Domain Layer
```text
domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/model/
├── CatalogItem.kt
├── CatalogItemStatus.kt
├── CatalogItemFormat.kt
├── CatalogScore.kt
├── CatalogSort.kt
├── CatalogQuery.kt
├── CatalogPage.kt
└── CatalogError.kt

domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/service/
└── CatalogProvider.kt

domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/
├── MaterializeCanonicalTitleFromCatalog.kt
├── SearchCatalog.kt
└── GetDiscoverFeed.kt
```

### Data Layer
```text
data/src/main/java/tachiyomi/data/tsuzuki/kitsu/dto/
├── KitsuMangaResponse.kt
├── KitsuSingleMangaResponse.kt
├── KitsuMangaResource.kt
├── KitsuMangaAttributes.kt
├── KitsuTitles.kt
├── KitsuImage.kt
├── KitsuMeta.kt
└── KitsuLinks.kt

data/src/main/java/tachiyomi/data/tsuzuki/kitsu/client/
├── KitsuClient.kt
└── KitsuHttpClient.kt

data/src/main/java/tachiyomi/data/tsuzuki/kitsu/
└── KitsuCatalogProvider.kt
```

### Dependency Injection
```text
app/src/main/java/mihon/app/di/tsuzuki/
└── CatalogModule.kt
```

### Minimal UI
```text
app/src/main/java/eu/kanade/presentation/tsuzuki/catalog/
├── CatalogScreen.kt
├── CatalogItemCard.kt
└── CatalogItemDetailSheet.kt

app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/catalog/
├── CatalogScreen.kt
└── CatalogScreenModel.kt
```

The UI task may make one minimal edit to an existing Voyager/navigation entry point to make this screen reachable. Do not redesign the complete main navigation in this milestone.

### Test Fixtures & Unit Tests
```text
app/src/test/resources/kitsu/
├── kitsu_manga_search_berserk.json
├── kitsu_trending_manga.json
└── kitsu_manga_details_single.json

domain/src/test/java/tachiyomi/domain/tsuzuki/catalog/model/
└── CatalogModelTest.kt

domain/src/test/java/tachiyomi/domain/tsuzuki/interactor/
├── MaterializeCanonicalTitleFromCatalogTest.kt
├── SearchCatalogTest.kt
└── GetDiscoverFeedTest.kt

app/src/test/java/tachiyomi/data/tsuzuki/kitsu/
├── KitsuDtoSerializationTest.kt
└── KitsuCatalogProviderTest.kt
```

---

## Kitsu transport verification rule

The architecture decision for this milestone is to use Kitsu's public Edge REST / JSON:API surface behind `KitsuClient`, not tracker-domain code. Before Task 2 hardcodes a base URL, query parameter, sort key, status value, or trending route, verify that exact contract against a current authoritative Kitsu source or a live non-mutating response. Use one verified canonical endpoint in production code; do not invent automatic host fallbacks between `kitsu.app` and `kitsu.io`.

The existing Mihon tracker at `app/src/main/java/eu/kanade/tachiyomi/data/track/kitsu/KitsuApi.kt` is reference material for repository networking/serialization patterns only. It uses `https://kitsu.app/api/graphql` with Kotlinx Serialization; tracker semantics must not leak into `CatalogProvider`.

---

## Detailed implementation tasks

### Task 1: Domain models, error taxonomy, and provider contracts

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogItemStatus.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogItemFormat.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogScore.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogSort.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogQuery.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogItem.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogPage.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogError.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/service/CatalogProvider.kt`
- Create: `domain/src/test/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogModelTest.kt`

**Interfaces:**
- Produces: `CatalogItem`, `CatalogScore`, `CatalogQuery`, `CatalogPage`, `CatalogError`, and `CatalogProvider`.

- [ ] **Step 1: Write the failing domain model tests**

Create `domain/src/test/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogModelTest.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.catalog.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class CatalogModelTest {

    @Test
    fun `catalog item preserves provider-specific identity and score`() {
        val score = CatalogScore(
            provider = "kitsu",
            value = 84.5,
            maxValue = 100.0,
            voteCount = 12500,
        )
        val item = CatalogItem(
            provider = "kitsu",
            providerId = "1234",
            title = "Berserk",
            titles = mapOf("en" to "Berserk", "ja_jp" to "ベルセルク"),
            synopsis = "Guts, a former mercenary...",
            coverUrl = "https://kitsu.io/covers/berserk.jpg",
            bannerUrl = "https://kitsu.io/banners/berserk.jpg",
            status = CatalogItemStatus.ONGOING,
            format = CatalogItemFormat.MANGA,
            score = score,
            genres = listOf("Action", "Dark Fantasy"),
            tags = listOf("Demons", "Mercenaries"),
            startDate = "1989-08-25",
            endDate = null,
            chapterCount = null,
            volumeCount = 41,
        )

        item.provider shouldBe "kitsu"
        item.providerId shouldBe "1234"
        item.title shouldBe "Berserk"
        item.score?.value shouldBe 84.5
        item.score?.maxValue shouldBe 100.0
        item.status shouldBe CatalogItemStatus.ONGOING
        item.format shouldBe CatalogItemFormat.MANGA
    }

    @Test
    fun `catalog error taxonomy classifies network, http, and rate limit errors`() {
        val netErr: CatalogError = CatalogError.NetworkError(IllegalStateException("No route to host"))
        val httpErr: CatalogError = CatalogError.HttpError(404, "Not Found")
        val rateErr: CatalogError = CatalogError.RateLimitExceeded(retryAfterSeconds = 60)

        netErr.shouldBeInstanceOf<CatalogError.NetworkError>()
        httpErr.shouldBeInstanceOf<CatalogError.HttpError>()
        rateErr.shouldBeInstanceOf<CatalogError.RateLimitExceeded>()
        (rateErr as CatalogError.RateLimitExceeded).retryAfterSeconds shouldBe 60
    }

    @Test
    fun `catalog query defaults provide standard pagination and sort`() {
        val query = CatalogQuery(query = "Monster")

        query.query shouldBe "Monster"
        query.sort shouldBe CatalogSort.POPULARITY_DESC
        query.offset shouldBe 0
        query.limit shouldBe 20
    }
}
```

- [ ] **Step 2: Commit and push the red test**

```bash
git add domain/src/test/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogModelTest.kt
git commit -m "test(tsuzuki): add catalog domain model and error contract tests"
git push
```

- [ ] **Step 3: Confirm expected Fast CI failure**

Confirm compilation fails in `Kotlin Compile` and `Unit Tests` due to unresolved references in `tachiyomi.domain.tsuzuki.catalog.model`.

- [ ] **Step 4: Implement domain status, format, score, and sort models**

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogItemStatus.kt`:
```kotlin
package tachiyomi.domain.tsuzuki.catalog.model

enum class CatalogItemStatus {
    ONGOING,
    COMPLETED,
    CANCELLED,
    ON_HIATUS,
    UNKNOWN,
}
```

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogItemFormat.kt`:
```kotlin
package tachiyomi.domain.tsuzuki.catalog.model

enum class CatalogItemFormat {
    MANGA,
    NOVEL,
    ONE_SHOT,
    MANHWA,
    MANHUA,
    DOUJIN,
    UNKNOWN,
}
```

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogScore.kt`:
```kotlin
package tachiyomi.domain.tsuzuki.catalog.model

data class CatalogScore(
    val provider: String,
    val value: Double,
    val maxValue: Double = 100.0,
    val voteCount: Int? = null,
)
```

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogSort.kt`:
```kotlin
package tachiyomi.domain.tsuzuki.catalog.model

enum class CatalogSort {
    POPULARITY_DESC,
    POPULARITY_ASC,
    RATING_DESC,
    RATING_ASC,
    UPDATED_DESC,
    RELEVANCE,
}
```

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogQuery.kt`:
```kotlin
package tachiyomi.domain.tsuzuki.catalog.model

data class CatalogQuery(
    val query: String? = null,
    val sort: CatalogSort = CatalogSort.POPULARITY_DESC,
    val genres: List<String> = emptyList(),
    val status: CatalogItemStatus? = null,
    val offset: Int = 0,
    val limit: Int = 20,
)
```

- [ ] **Step 5: Implement `CatalogItem`, `CatalogPage`, and `CatalogError`**

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogItem.kt`:
```kotlin
package tachiyomi.domain.tsuzuki.catalog.model

data class CatalogItem(
    val provider: String,
    val providerId: String,
    val title: String,
    val titles: Map<String, String> = emptyMap(),
    val synopsis: String? = null,
    val coverUrl: String? = null,
    val bannerUrl: String? = null,
    val status: CatalogItemStatus = CatalogItemStatus.UNKNOWN,
    val format: CatalogItemFormat = CatalogItemFormat.UNKNOWN,
    val score: CatalogScore? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val startDate: String? = null,
    val endDate: String? = null,
    val chapterCount: Int? = null,
    val volumeCount: Int? = null,
)
```

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogPage.kt`:
```kotlin
package tachiyomi.domain.tsuzuki.catalog.model

data class CatalogPage(
    val items: List<CatalogItem>,
    val hasNextPage: Boolean,
    val totalCount: Int? = null,
)
```

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/model/CatalogError.kt`:
```kotlin
package tachiyomi.domain.tsuzuki.catalog.model

sealed class CatalogError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NetworkError(cause: Throwable) : CatalogError("Network connectivity failure: ${cause.message}", cause)
    class HttpError(val statusCode: Int, message: String) : CatalogError("HTTP $statusCode: $message")
    class RateLimitExceeded(val retryAfterSeconds: Long? = null) : CatalogError("Provider rate limit exceeded")
    class SerializationError(cause: Throwable) : CatalogError("Serialization error: ${cause.message}", cause)
    class ProviderUnavailable(message: String, cause: Throwable? = null) : CatalogError(message, cause)
    class ItemNotFound(val providerId: String) : CatalogError("Catalog item not found: $providerId")
}
```

- [ ] **Step 6: Implement `CatalogProvider` contract**

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/service/CatalogProvider.kt`:
```kotlin
package tachiyomi.domain.tsuzuki.catalog.service

import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery

interface CatalogProvider {
    val providerId: String
    val displayName: String

    suspend fun search(query: CatalogQuery): Result<CatalogPage>
    suspend fun getTrending(offset: Int = 0, limit: Int = 20): Result<CatalogPage>
    suspend fun getPopular(offset: Int = 0, limit: Int = 20): Result<CatalogPage>
    suspend fun getDetails(providerId: String): Result<CatalogItem>
}
```

- [ ] **Step 7: Commit, push, and verify Fast CI**

```bash
git add domain/src/main/java/tachiyomi/domain/tsuzuki/catalog
git commit -m "feat(tsuzuki): implement catalog domain models and provider contract"
git push
```

Verify Fast CI runs: `Format`, `Kotlin Compile`, `Unit Tests`, and `SQLDelight Migrations` must all be green.

---

### Task 2: Kitsu HTTP client, serialization DTOs, and error mapping

**Files:**
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/dto/KitsuTitles.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/dto/KitsuImage.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/dto/KitsuMeta.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/dto/KitsuLinks.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/dto/KitsuMangaAttributes.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/dto/KitsuMangaResource.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/dto/KitsuMangaResponse.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/dto/KitsuSingleMangaResponse.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/client/KitsuClient.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/client/KitsuHttpClient.kt`
- Create: `app/src/test/resources/kitsu/kitsu_manga_search_berserk.json`
- Create: `app/src/test/resources/kitsu/kitsu_trending_manga.json`
- Create: `app/src/test/resources/kitsu/kitsu_manga_details_single.json`
- Create: `app/src/test/java/tachiyomi/data/tsuzuki/kitsu/KitsuDtoSerializationTest.kt`
- Do not modify `data/build.gradle.kts` solely to create a new test source set for this milestone.

**Interfaces:**
- Consumes: `eu.kanade.tachiyomi.network.NetworkHelper`, `kotlinx.serialization.json.Json`.
- Produces: a small Android-free `KitsuClient` transport contract plus `KitsuHttpClient`, which implements it and returns typed `Result<KitsuMangaResponse>` / `Result<KitsuSingleMangaResponse>`.

- [ ] **Step 1: Use the existing app test harness; do not create a new `:data` test setup**

The repository currently has no established `data/src/test` tree. Keep Kitsu DTO/provider contract tests under `app/src/test`, which already has the standard project test dependencies and depends on `:data` and `:domain`.

Do not modify `data/build.gradle.kts` only to create test infrastructure. If implementation evidence later proves a data-module test is necessary, stop and justify that infrastructure change separately.
- [ ] **Step 2: Add JSON fixtures for Kitsu edge responses**

Create `app/src/test/resources/kitsu/kitsu_manga_search_berserk.json`:
```json
{
  "data": [
    {
      "id": "1234",
      "type": "manga",
      "attributes": {
        "createdAt": "2013-12-18T13:48:38.892Z",
        "updatedAt": "2026-09-18T10:00:00.000Z",
        "slug": "berserk",
        "synopsis": "Guts is a skilled wanderer...",
        "description": "Guts is a skilled wanderer...",
        "titles": {
          "en": "Berserk",
          "en_jp": "Berserk",
          "ja_jp": "ベルセルク"
        },
        "canonicalTitle": "Berserk",
        "abbreviatedTitles": [],
        "averageRating": "84.56",
        "userCount": 42000,
        "favoritesCount": 8500,
        "startDate": "1989-08-25",
        "endDate": null,
        "status": "current",
        "subtype": "manga",
        "posterImage": {
          "tiny": "https://media.kitsu.io/manga/poster_images/1234/tiny.jpg",
          "small": "https://media.kitsu.io/manga/poster_images/1234/small.jpg",
          "medium": "https://media.kitsu.io/manga/poster_images/1234/medium.jpg",
          "large": "https://media.kitsu.io/manga/poster_images/1234/large.jpg",
          "original": "https://media.kitsu.io/manga/poster_images/1234/original.jpg"
        },
        "coverImage": {
          "tiny": "https://media.kitsu.io/manga/cover_images/1234/tiny.jpg",
          "small": "https://media.kitsu.io/manga/cover_images/1234/small.jpg",
          "large": "https://media.kitsu.io/manga/cover_images/1234/large.jpg",
          "original": "https://media.kitsu.io/manga/cover_images/1234/original.jpg"
        },
        "chapterCount": null,
        "volumeCount": 41,
        "serialization": "Young Animal"
      }
    }
  ],
  "meta": {
    "count": 1
  },
  "links": {
    "first": "https://kitsu.io/api/edge/manga?page%5Blimit%5D=10&page%5Boffset%5D=0",
    "last": "https://kitsu.io/api/edge/manga?page%5Blimit%5D=10&page%5Boffset%5D=0"
  }
}
```

Create `app/src/test/resources/kitsu/kitsu_trending_manga.json`:
```json
{
  "data": [
    {
      "id": "5678",
      "type": "manga",
      "attributes": {
        "slug": "chainsaw-man",
        "synopsis": "Denji was a small-time devil hunter...",
        "titles": {
          "en": "Chainsaw Man",
          "ja_jp": "チェンソーマン"
        },
        "canonicalTitle": "Chainsaw Man",
        "averageRating": "83.12",
        "userCount": 35000,
        "status": "current",
        "subtype": "manga",
        "posterImage": {
          "medium": "https://media.kitsu.io/manga/poster_images/5678/medium.jpg"
        }
      }
    }
  ]
}
```

Create `app/src/test/resources/kitsu/kitsu_manga_details_single.json`:
```json
{
  "data": {
    "id": "1234",
    "type": "manga",
    "attributes": {
      "slug": "berserk",
      "synopsis": "Guts is a skilled wanderer...",
      "titles": {
        "en": "Berserk"
      },
      "canonicalTitle": "Berserk",
      "averageRating": "84.56",
      "userCount": 42000,
      "status": "current",
      "subtype": "manga",
      "posterImage": {
        "medium": "https://media.kitsu.io/manga/poster_images/1234/medium.jpg"
      }
    }
  }
}
```

- [ ] **Step 3: Write red test for DTO serialization**

Create `app/src/test/java/tachiyomi/data/tsuzuki/kitsu/KitsuDtoSerializationTest.kt`:

```kotlin
package tachiyomi.data.tsuzuki.kitsu

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResponse
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuSingleMangaResponse

class KitsuDtoSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `parse search manga response fixture successfully`() {
        val jsonString = javaClass.getResource("/kitsu/kitsu_manga_search_berserk.json")!!.readText()
        val response = json.decodeFromString<KitsuMangaResponse>(jsonString)

        response.data.size shouldBe 1
        val item = response.data.first()
        item.id shouldBe "1234"
        item.type shouldBe "manga"
        item.attributes.canonicalTitle shouldBe "Berserk"
        item.attributes.averageRating shouldBe "84.56"
        item.attributes.userCount shouldBe 42000
        item.attributes.posterImage?.medium shouldNotBe null
        response.meta?.count shouldBe 1
    }

    @Test
    fun `parse trending manga response fixture successfully`() {
        val jsonString = javaClass.getResource("/kitsu/kitsu_trending_manga.json")!!.readText()
        val response = json.decodeFromString<KitsuMangaResponse>(jsonString)

        response.data.size shouldBe 1
        response.data.first().attributes.canonicalTitle shouldBe "Chainsaw Man"
    }

    @Test
    fun `parse single manga details response successfully`() {
        val jsonString = javaClass.getResource("/kitsu/kitsu_manga_details_single.json")!!.readText()
        val response = json.decodeFromString<KitsuSingleMangaResponse>(jsonString)

        response.data.id shouldBe "1234"
        response.data.attributes.canonicalTitle shouldBe "Berserk"
    }
}
```

- [ ] **Step 4: Implement Kitsu JSON:API serialization DTOs**

Create `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/dto/KitsuTitles.kt`:
```kotlin
package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class KitsuTitles(
    val en: String? = null,
    @SerialName("en_jp") val enJp: String? = null,
    @SerialName("ja_jp") val jaJp: String? = null,
    @SerialName("en_us") val enUs: String? = null,
)
```

Create `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/dto/KitsuImage.kt`:
```kotlin
package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuImage(
    val tiny: String? = null,
    val small: String? = null,
    val medium: String? = null,
    val large: String? = null,
    val original: String? = null,
)
```

Create `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/dto/KitsuMeta.kt`:
```kotlin
package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuMeta(
    val count: Int? = null,
)
```

Create `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/dto/KitsuLinks.kt`:
```kotlin
package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuLinks(
    val first: String? = null,
    val next: String? = null,
    val prev: String? = null,
    val last: String? = null,
)
```

Create `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/dto/KitsuMangaAttributes.kt`:
```kotlin
package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuMangaAttributes(
    val canonicalTitle: String? = null,
    val titles: KitsuTitles? = null,
    val synopsis: String? = null,
    val description: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val averageRating: String? = null,
    val userCount: Int? = null,
    val favoritesCount: Int? = null,
    val status: String? = null,
    val subtype: String? = null,
    val posterImage: KitsuImage? = null,
    val coverImage: KitsuImage? = null,
    val chapterCount: Int? = null,
    val volumeCount: Int? = null,
    val serialization: String? = null,
)
```

Create `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/dto/KitsuMangaResource.kt`:
```kotlin
package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuMangaResource(
    val id: String,
    val type: String,
    val attributes: KitsuMangaAttributes,
)
```

Create `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/dto/KitsuMangaResponse.kt`:
```kotlin
package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuMangaResponse(
    val data: List<KitsuMangaResource> = emptyList(),
    val meta: KitsuMeta? = null,
    val links: KitsuLinks? = null,
)
```

Create `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/dto/KitsuSingleMangaResponse.kt`:
```kotlin
package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuSingleMangaResponse(
    val data: KitsuMangaResource,
)
```

- [ ] **Step 5: Implement the mockable `KitsuClient` boundary and `KitsuHttpClient` with OkHttp and typed error mapping**

Create `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/client/KitsuClient.kt`:

```kotlin
package tachiyomi.data.tsuzuki.kitsu.client

import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResponse
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuSingleMangaResponse

interface KitsuClient {
    suspend fun searchManga(
        query: String?,
        offset: Int,
        limit: Int,
        sort: String?,
        status: String?,
    ): Result<KitsuMangaResponse>

    suspend fun getTrendingManga(limit: Int): Result<KitsuMangaResponse>

    suspend fun getMangaById(id: String): Result<KitsuSingleMangaResponse>
}
```

Create `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/client/KitsuHttpClient.kt`:

```kotlin
package tachiyomi.data.tsuzuki.kitsu.client

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResponse
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuSingleMangaResponse
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import java.io.IOException

@Inject
@SingleIn(AppScope::class)
class KitsuHttpClient(
    private val network: NetworkHelper,
    private val json: Json,
) : KitsuClient {
    private val baseUrl: HttpUrl = "https://kitsu.io/api/edge/".toHttpUrl()

    private val kitsuJson: Json = Json(json) {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private fun requestBuilder(url: HttpUrl): Request.Builder {
        return GET(url.toString()).newBuilder()
            .header("Accept", "application/vnd.api+json")
            .header("Content-Type", "application/vnd.api+json")
    }

    override suspend fun searchManga(
        query: String?,
        offset: Int,
        limit: Int,
        sort: String?,
        status: String?,
    ): Result<KitsuMangaResponse> {
        val urlBuilder = baseUrl.newBuilder().addPathSegment("manga")
        if (!query.isNullOrBlank()) {
            urlBuilder.addQueryParameter("filter[text]", query.trim())
        }
        if (!status.isNullOrBlank()) {
            urlBuilder.addQueryParameter("filter[status]", status)
        }
        if (!sort.isNullOrBlank()) {
            urlBuilder.addQueryParameter("sort", sort)
        }
        urlBuilder.addQueryParameter("page[offset]", offset.toString())
        urlBuilder.addQueryParameter("page[limit]", limit.toString())

        return executeRequest(urlBuilder.build())
    }

    override suspend fun getTrendingManga(limit: Int): Result<KitsuMangaResponse> {
        val url = baseUrl.newBuilder()
            .addPathSegment("trending")
            .addPathSegment("manga")
            .addQueryParameter("limit", limit.toString())
            .build()

        return executeRequest(url)
    }

    override suspend fun getMangaById(id: String): Result<KitsuSingleMangaResponse> {
        val url = baseUrl.newBuilder()
            .addPathSegment("manga")
            .addPathSegment(id)
            .build()

        return executeRequest(url)
    }

    private suspend inline fun <reified T> executeRequest(url: HttpUrl): Result<T> {
        return try {
            val response: Response = network.client.newCall(requestBuilder(url).build()).await()
            when (response.code) {
                in 200..299 -> {
                    val bodyString = response.body.string()
                    val parsed = kitsuJson.decodeFromString<T>(bodyString)
                    Result.success(parsed)
                }
                404 -> Result.failure(CatalogError.ItemNotFound(url.encodedPath))
                429 -> {
                    val retryAfter = response.header("Retry-After")?.toLongOrNull()
                    Result.failure(CatalogError.RateLimitExceeded(retryAfter))
                }
                in 500..599 -> Result.failure(CatalogError.ProviderUnavailable("Kitsu server error: ${response.code}"))
                else -> Result.failure(CatalogError.HttpError(response.code, response.message))
            }
        } catch (e: SerializationException) {
            Result.failure(CatalogError.SerializationError(e))
        } catch (e: IOException) {
            Result.failure(CatalogError.NetworkError(e))
        } catch (e: Exception) {
            Result.failure(CatalogError.ProviderUnavailable("Unexpected failure: ${e.message}", e))
        }
    }
}
```

- [ ] **Step 6: Commit, push, and verify Fast CI**

```bash
git add data/src/main/java/tachiyomi/data/tsuzuki/kitsu app/src/test
git commit -m "feat(tsuzuki): add Kitsu HTTP client and serialization DTOs"
git push
```

Verify Fast CI runs and all four jobs are green.

---

### Task 3: `KitsuCatalogProvider` implementation

**Files:**
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/KitsuCatalogProvider.kt`
- Create: `app/src/test/java/tachiyomi/data/tsuzuki/kitsu/KitsuCatalogProviderTest.kt`

**Interfaces:**
- Consumes: `CatalogProvider` contract from Task 1 and the mockable `KitsuClient` transport from Task 2.
- Produces: `KitsuCatalogProvider` providing domain `CatalogItem` and `CatalogPage` models.

- [ ] **Step 1: Write red tests for `KitsuCatalogProvider`**

Create `app/src/test/java/tachiyomi/data/tsuzuki/kitsu/KitsuCatalogProviderTest.kt`:

```kotlin
package tachiyomi.data.tsuzuki.kitsu

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.data.tsuzuki.kitsu.client.KitsuClient
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResponse
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuSingleMangaResponse
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort

class KitsuCatalogProviderTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    @Test
    fun `search maps Kitsu resources to domain catalog items correctly`() = runTest {
        val searchJson = javaClass.getResource("/kitsu/kitsu_manga_search_berserk.json")!!.readText()
        val mockResponse = json.decodeFromString<KitsuMangaResponse>(searchJson)

        val provider = KitsuCatalogProvider(
            httpClient = FakeKitsuClient(searchResult = Result.success(mockResponse)),
        )

        val result = provider.search(CatalogQuery(query = "Berserk", sort = CatalogSort.POPULARITY_DESC))
        result.isSuccess shouldBe true

        val page = result.getOrThrow()
        page.items.size shouldBe 1

        val item = page.items.first()
        item.provider shouldBe "kitsu"
        item.providerId shouldBe "1234"
        item.title shouldBe "Berserk"
        item.status shouldBe CatalogItemStatus.ONGOING
        item.format shouldBe CatalogItemFormat.MANGA
        item.score?.value shouldBe 84.56
        item.score?.maxValue shouldBe 100.0
        item.score?.voteCount shouldBe 42000
        item.coverUrl shouldBe "https://media.kitsu.io/manga/poster_images/1234/medium.jpg"
        item.bannerUrl shouldBe "https://media.kitsu.io/manga/cover_images/1234/large.jpg"
    }

    @Test
    fun `getDetails maps single resource to catalog item`() = runTest {
        val detailJson = javaClass.getResource("/kitsu/kitsu_manga_details_single.json")!!.readText()
        val mockResponse = json.decodeFromString<KitsuSingleMangaResponse>(detailJson)

        val provider = KitsuCatalogProvider(
            httpClient = FakeKitsuClient(detailResult = Result.success(mockResponse)),
        )

        val result = provider.getDetails("1234")
        result.isSuccess shouldBe true

        val item = result.getOrThrow()
        item.providerId shouldBe "1234"
        item.title shouldBe "Berserk"
    }

    private class FakeKitsuClient(
        private val searchResult: Result<KitsuMangaResponse> = Result.success(KitsuMangaResponse()),
        private val detailResult: Result<KitsuSingleMangaResponse>? = null,
    ) : KitsuClient {
        override suspend fun searchManga(
            query: String?,
            offset: Int,
            limit: Int,
            sort: String?,
            status: String?,
        ): Result<KitsuMangaResponse> = searchResult

        override suspend fun getTrendingManga(limit: Int): Result<KitsuMangaResponse> = searchResult

        override suspend fun getMangaById(id: String): Result<KitsuSingleMangaResponse> =
            detailResult ?: Result.failure(IllegalStateException("No detail result configured"))
    }
}
```

Provider tests must fake `KitsuClient`; they must not construct Android `Context` or subclass the concrete network client.

- [ ] **Step 2: Implement `KitsuCatalogProvider`**

Create `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/KitsuCatalogProvider.kt`:

```kotlin
package tachiyomi.data.tsuzuki.kitsu

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.tsuzuki.kitsu.client.KitsuClient
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResource
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.catalog.service.CatalogProvider

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class KitsuCatalogProvider(
    private val httpClient: KitsuClient,
) : CatalogProvider {

    override val providerId: String = "kitsu"
    override val displayName: String = "Kitsu"

    override suspend fun search(query: CatalogQuery): Result<CatalogPage> {
        val sortParam = when (query.sort) {
            CatalogSort.POPULARITY_DESC -> "-userCount"
            CatalogSort.POPULARITY_ASC -> "userCount"
            CatalogSort.RATING_DESC -> "-averageRating"
            CatalogSort.RATING_ASC -> "averageRating"
            CatalogSort.UPDATED_DESC -> "-updatedAt"
            CatalogSort.RELEVANCE -> null
        }
        val statusParam = when (query.status) {
            CatalogItemStatus.ONGOING -> "current"
            CatalogItemStatus.COMPLETED -> "finished"
            CatalogItemStatus.ON_HIATUS -> "unapproved"
            CatalogItemStatus.CANCELLED -> "cancelled"
            CatalogItemStatus.UNKNOWN, null -> null
        }

        return httpClient.searchManga(
            query = query.query,
            offset = query.offset,
            limit = query.limit,
            sort = sortParam,
            status = statusParam,
        ).map { response ->
            val items = response.data.map(::mapResourceToItem)
            val hasNext = response.links?.next != null || (response.meta?.count != null && query.offset + items.size < response.meta.count)
            CatalogPage(
                items = items,
                hasNextPage = hasNext,
                totalCount = response.meta?.count,
            )
        }
    }

    override suspend fun getTrending(offset: Int, limit: Int): Result<CatalogPage> {
        return httpClient.getTrendingManga(limit).map { response ->
            val items = response.data.map(::mapResourceToItem)
            CatalogPage(
                items = items,
                hasNextPage = false,
                totalCount = items.size,
            )
        }
    }

    override suspend fun getPopular(offset: Int, limit: Int): Result<CatalogPage> {
        return search(
            CatalogQuery(
                sort = CatalogSort.POPULARITY_DESC,
                offset = offset,
                limit = limit,
            ),
        )
    }

    override suspend fun getDetails(providerId: String): Result<CatalogItem> {
        return httpClient.getMangaById(providerId).map { response ->
            mapResourceToItem(response.data)
        }
    }

    internal fun mapResourceToItem(resource: KitsuMangaResource): CatalogItem {
        val attr = resource.attributes
        val bestTitle = attr.canonicalTitle
            ?: attr.titles?.en
            ?: attr.titles?.enJp
            ?: attr.titles?.jaJp
            ?: "Unknown"

        val titlesMap = buildMap {
            attr.canonicalTitle?.let { put("canonical", it) }
            attr.titles?.en?.let { put("en", it) }
            attr.titles?.enJp?.let { put("en_jp", it) }
            attr.titles?.jaJp?.let { put("ja_jp", it) }
            attr.titles?.enUs?.let { put("en_us", it) }
        }

        val mappedStatus = when (attr.status?.lowercase()) {
            "current" -> CatalogItemStatus.ONGOING
            "finished" -> CatalogItemStatus.COMPLETED
            "cancelled" -> CatalogItemStatus.CANCELLED
            "unapproved" -> CatalogItemStatus.ON_HIATUS
            else -> CatalogItemStatus.UNKNOWN
        }

        val mappedFormat = when (attr.subtype?.lowercase()) {
            "manga" -> CatalogItemFormat.MANGA
            "novel" -> CatalogItemFormat.NOVEL
            "oneshot" -> CatalogItemFormat.ONE_SHOT
            "doujin" -> CatalogItemFormat.DOUJIN
            "manhwa" -> CatalogItemFormat.MANHWA
            "manhua" -> CatalogItemFormat.MANHUA
            else -> CatalogItemFormat.UNKNOWN
        }

        val score = attr.averageRating?.toDoubleOrNull()?.let { rating ->
            CatalogScore(
                provider = providerId,
                value = rating,
                maxValue = 100.0,
                voteCount = attr.userCount,
            )
        }

        val cover = attr.posterImage?.medium
            ?: attr.posterImage?.small
            ?: attr.posterImage?.large
            ?: attr.posterImage?.original

        val banner = attr.coverImage?.large
            ?: attr.coverImage?.original
            ?: attr.coverImage?.small

        return CatalogItem(
            provider = providerId,
            providerId = resource.id,
            title = bestTitle,
            titles = titlesMap,
            synopsis = attr.synopsis ?: attr.description,
            coverUrl = cover,
            bannerUrl = banner,
            status = mappedStatus,
            format = mappedFormat,
            score = score,
            startDate = attr.startDate,
            endDate = attr.endDate,
            chapterCount = attr.chapterCount,
            volumeCount = attr.volumeCount,
        )
    }
}
```

- [ ] **Step 3: Commit, push, and verify Fast CI**

```bash
git add data/src/main/java/tachiyomi/data/tsuzuki/kitsu/KitsuCatalogProvider.kt app/src/test
git commit -m "feat(tsuzuki): implement KitsuCatalogProvider"
git push
```

Verify Fast CI runs and all four jobs are green.

---

### Task 4: Catalog-to-Canonical materialization interactor

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitleFromCatalog.kt`
- Create: `domain/src/test/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitleFromCatalogTest.kt`

**Interfaces:**
- Consumes: `CanonicalTitleRepository`, `CatalogItem`.
- Produces: `MaterializeCanonicalTitleFromCatalog.execute(catalogItem): CanonicalTitle`.

- [ ] **Step 1: Write red tests for catalog-to-canonical materialization**

Create `domain/src/test/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitleFromCatalogTest.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.interactor

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class MaterializeCanonicalTitleFromCatalogTest {

    @Test
    fun `materializing catalog item creates new canonical title with UUID and external identity`() = runTest {
        val repository = FakeTitleRepository()
        val interactor = MaterializeCanonicalTitleFromCatalog(
            repository = repository,
            idFactory = { "uuid-12345" },
            clock = { 5000L },
        )

        val catalogItem = CatalogItem(
            provider = "kitsu",
            providerId = "999",
            title = "Vinland Saga",
        )

        val title = interactor.execute(catalogItem)

        // Spec Invariant: CanonicalTitle.id MUST NOT be the provider ID
        title.id shouldBe "uuid-12345"
        title.id shouldNotBe catalogItem.providerId
        title.displayTitle shouldBe "Vinland Saga"
        title.identityState shouldBe CanonicalIdentityState.RESOLVED

        // Spec Invariant: Kitsu ID is attached as ExternalIdentity
        repository.identities.size shouldBe 1
        val identity = repository.identities.first()
        identity.canonicalTitleId shouldBe "uuid-12345"
        identity.provider shouldBe "kitsu"
        identity.externalId shouldBe "999"
        identity.verified shouldBe true
    }

    @Test
    fun `materializing already existing catalog item is idempotent`() = runTest {
        val repository = FakeTitleRepository()
        val interactor = MaterializeCanonicalTitleFromCatalog(
            repository = repository,
            idFactory = { "uuid-first" },
            clock = { 5000L },
        )

        val catalogItem = CatalogItem(
            provider = "kitsu",
            providerId = "999",
            title = "Vinland Saga",
        )

        val first = interactor.execute(catalogItem)
        val second = interactor.execute(catalogItem)

        first.id shouldBe "uuid-first"
        second.id shouldBe first.id
        repository.titles.size shouldBe 1
        repository.identities.size shouldBe 1
    }

    private class FakeTitleRepository : CanonicalTitleRepository {
        val titles = mutableMapOf<String, CanonicalTitle>()
        val identities = mutableListOf<ExternalIdentity>()
        private val flow = MutableStateFlow<CanonicalTitle?>(null)

        override suspend fun getById(id: String): CanonicalTitle? = titles[id]
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = flow
        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? {
            val titleId = identities.firstOrNull { it.provider == provider && it.externalId == externalId }?.canonicalTitleId
            return titleId?.let { titles[it] }
        }
        override suspend fun insert(title: CanonicalTitle) {
            titles[title.id] = title
            flow.value = title
        }
        override suspend fun addExternalIdentity(identity: ExternalIdentity) {
            identities += identity
        }
    }
}
```

- [ ] **Step 2: Commit and push red test**

```bash
git add domain/src/test/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitleFromCatalogTest.kt
git commit -m "test(tsuzuki): verify catalog to canonical materialization invariants"
git push
```

- [ ] **Step 3: Implement `MaterializeCanonicalTitleFromCatalog`**

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitleFromCatalog.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import java.util.UUID
import kotlin.time.Clock

class MaterializeCanonicalTitleFromCatalog internal constructor(
    private val repository: CanonicalTitleRepository,
    private val idFactory: () -> String,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        repository: CanonicalTitleRepository,
    ) : this(
        repository = repository,
        idFactory = { UUID.randomUUID().toString() },
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun execute(catalogItem: CatalogItem): CanonicalTitle {
        // Return existing title if already mapped to this provider identity
        repository.getByExternalIdentity(catalogItem.provider, catalogItem.providerId)?.let {
            return it
        }

        val now = clock()
        val title = CanonicalTitle(
            id = idFactory(),
            displayTitle = catalogItem.title,
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = now,
            updatedAt = now,
        )

        repository.insert(title)
        repository.addExternalIdentity(
            ExternalIdentity(
                canonicalTitleId = title.id,
                provider = catalogItem.provider,
                externalId = catalogItem.providerId,
                verified = true,
                createdAt = now,
            ),
        )

        return title
    }
}
```

- [ ] **Step 4: Commit, push, and verify Fast CI**

```bash
git add domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitleFromCatalog.kt
git commit -m "feat(tsuzuki): implement MaterializeCanonicalTitleFromCatalog"
git push
```

Verify Fast CI runs and all four jobs are green.

---

### Task 5: Unified Search & Discover interactors

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/SearchCatalog.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/GetDiscoverFeed.kt`
- Create: `domain/src/test/java/tachiyomi/domain/tsuzuki/interactor/SearchCatalogTest.kt`
- Create: `domain/src/test/java/tachiyomi/domain/tsuzuki/interactor/GetDiscoverFeedTest.kt`

**Interfaces:**
- Consumes: `CatalogProvider`.
- Produces: `SearchCatalog.execute(...)`, `GetDiscoverFeed.execute(...)`.

- [ ] **Step 1: Write red tests for `SearchCatalog` and `GetDiscoverFeed`**

Create `domain/src/test/java/tachiyomi/domain/tsuzuki/interactor/SearchCatalogTest.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.service.CatalogProvider

class SearchCatalogTest {

    @Test
    fun `search delegates to provider and returns page on success`() = runTest {
        val fakeProvider = FakeCatalogProvider(
            searchResult = Result.success(CatalogPage(items = listOf(CatalogItem("kitsu", "1", "Monster")), hasNextPage = false)),
        )
        val interactor = SearchCatalog(fakeProvider)

        val result = interactor.execute("Monster")
        result.isSuccess shouldBe true
        result.getOrThrow().items.first().title shouldBe "Monster"
    }

    @Test
    fun `search returns typed failure gracefully when provider fails`() = runTest {
        val fakeProvider = FakeCatalogProvider(
            searchResult = Result.failure(CatalogError.RateLimitExceeded(30)),
        )
        val interactor = SearchCatalog(fakeProvider)

        val result = interactor.execute("Monster")
        result.isFailure shouldBe true
        (result.exceptionOrNull() is CatalogError.RateLimitExceeded) shouldBe true
    }

    private class FakeCatalogProvider(
        var searchResult: Result<CatalogPage> = Result.success(CatalogPage(emptyList(), false)),
    ) : CatalogProvider {
        override val providerId: String = "kitsu"
        override val displayName: String = "Kitsu"
        override suspend fun search(query: CatalogQuery): Result<CatalogPage> = searchResult
        override suspend fun getTrending(offset: Int, limit: Int): Result<CatalogPage> = searchResult
        override suspend fun getPopular(offset: Int, limit: Int): Result<CatalogPage> = searchResult
        override suspend fun getDetails(providerId: String): Result<CatalogItem> = Result.failure(NotImplementedError())
    }
}
```

Create `domain/src/test/java/tachiyomi/domain/tsuzuki/interactor/GetDiscoverFeedTest.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.service.CatalogProvider

class GetDiscoverFeedTest {

    @Test
    fun `discover feed returns trending and popular sections in parallel`() = runTest {
        val fakeProvider = FakeFeedProvider(
            trendingResult = Result.success(CatalogPage(listOf(CatalogItem("kitsu", "1", "Trending 1")), false)),
            popularResult = Result.success(CatalogPage(listOf(CatalogItem("kitsu", "2", "Popular 1")), false)),
        )
        val interactor = GetDiscoverFeed(fakeProvider)

        val feed = interactor.execute()
        feed.trending.isSuccess shouldBe true
        feed.popular.isSuccess shouldBe true
        feed.trending.getOrThrow().items.first().title shouldBe "Trending 1"
        feed.popular.getOrThrow().items.first().title shouldBe "Popular 1"
    }

    @Test
    fun `discover feed isolates failures between sections gracefully`() = runTest {
        val fakeProvider = FakeFeedProvider(
            trendingResult = Result.failure(CatalogError.NetworkError(Exception("Timeout"))),
            popularResult = Result.success(CatalogPage(listOf(CatalogItem("kitsu", "2", "Popular 1")), false)),
        )
        val interactor = GetDiscoverFeed(fakeProvider)

        val feed = interactor.execute()
        feed.trending.isFailure shouldBe true
        feed.popular.isSuccess shouldBe true
        feed.popular.getOrThrow().items.first().title shouldBe "Popular 1"
    }

    private class FakeFeedProvider(
        val trendingResult: Result<CatalogPage>,
        val popularResult: Result<CatalogPage>,
    ) : CatalogProvider {
        override val providerId: String = "kitsu"
        override val displayName: String = "Kitsu"
        override suspend fun search(query: CatalogQuery): Result<CatalogPage> = popularResult
        override suspend fun getTrending(offset: Int, limit: Int): Result<CatalogPage> = trendingResult
        override suspend fun getPopular(offset: Int, limit: Int): Result<CatalogPage> = popularResult
        override suspend fun getDetails(providerId: String): Result<CatalogItem> = Result.failure(NotImplementedError())
    }
}
```

- [ ] **Step 2: Implement `SearchCatalog` interactor**

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/SearchCatalog.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.catalog.service.CatalogProvider

class SearchCatalog @Inject constructor(
    private val provider: CatalogProvider,
) {
    suspend fun execute(
        query: String,
        offset: Int = 0,
        limit: Int = 20,
        sort: CatalogSort = CatalogSort.POPULARITY_DESC,
    ): Result<CatalogPage> {
        val catalogQuery = CatalogQuery(
            query = query,
            sort = sort,
            offset = offset,
            limit = limit,
        )
        return provider.search(catalogQuery)
    }
}
```

- [ ] **Step 3: Implement `GetDiscoverFeed` interactor**

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/GetDiscoverFeed.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.service.CatalogProvider

data class DiscoverFeed(
    val trending: Result<CatalogPage>,
    val popular: Result<CatalogPage>,
)

class GetDiscoverFeed @Inject constructor(
    private val provider: CatalogProvider,
) {
    suspend fun execute(limit: Int = 20): DiscoverFeed = coroutineScope {
        val trendingDeferred = async { provider.getTrending(limit = limit) }
        val popularDeferred = async { provider.getPopular(limit = limit) }

        DiscoverFeed(
            trending = trendingDeferred.await(),
            popular = popularDeferred.await(),
        )
    }
}
```

- [ ] **Step 4: Commit, push, and verify Fast CI**

```bash
git add domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/SearchCatalog.kt \
  domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/GetDiscoverFeed.kt \
  domain/src/test/java/tachiyomi/domain/tsuzuki/interactor/SearchCatalogTest.kt \
  domain/src/test/java/tachiyomi/domain/tsuzuki/interactor/GetDiscoverFeedTest.kt
git commit -m "feat(tsuzuki): implement SearchCatalog and GetDiscoverFeed interactors"
git push
```

Verify Fast CI runs and all four jobs are green.

---

### Task 6: Dependency injection & Metro wiring

**Files:**
- Create: `app/src/main/java/mihon/app/di/tsuzuki/CatalogModule.kt`
- Modify: `app/src/main/java/mihon/app/di/AppGraph.kt` (expose interactors and provider if desired)

**Interfaces:**
- Consumes: `KitsuCatalogProvider`, `KitsuHttpClient`, `SearchCatalog`, `GetDiscoverFeed`, `MaterializeCanonicalTitleFromCatalog`.
- Produces: Metro bindings contributing to `AppScope`.

- [ ] **Step 1: Inspect Metro registration rules**

Since `KitsuCatalogProvider` declares:
```kotlin
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class KitsuCatalogProvider(...) : CatalogProvider
```
and `KitsuHttpClient` declares:
```kotlin
@Inject
@SingleIn(AppScope::class)
class KitsuHttpClient(...)
```
and the interactors declare `@Inject constructor(...)`, Metro automatically satisfies and generates injection factories when compiled in `:app:compileDebugKotlin`.

- [ ] **Step 2: Create `CatalogModule` for binding safety and explicit interop**

Create `app/src/main/java/mihon/app/di/tsuzuki/CatalogModule.kt`:

```kotlin
package mihon.app.di.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import tachiyomi.domain.tsuzuki.catalog.service.CatalogProvider
import tachiyomi.domain.tsuzuki.interactor.GetDiscoverFeed
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog
import tachiyomi.domain.tsuzuki.interactor.SearchCatalog

@ContributesTo(AppScope::class)
interface CatalogModule {
    val catalogProvider: CatalogProvider
    val searchCatalog: SearchCatalog
    val getDiscoverFeed: GetDiscoverFeed
    val materializeCanonicalTitleFromCatalog: MaterializeCanonicalTitleFromCatalog
}
```

- [ ] **Step 3: Commit, push, and run Fast CI compilation check**

```bash
git add app/src/main/java/mihon/app/di/tsuzuki/CatalogModule.kt
git commit -m "feat(tsuzuki): wire catalog module into Metro AppScope"
git push
```

- [ ] **Step 4: Verify Kotlin compile in Fast CI**

Confirm `:app:compileDebugKotlin` succeeds. Metro will validate that all declared graph bindings resolve without missing dependencies.

---

### Task 7: Comprehensive contract tests & error resilience verification

**Files:**
- Create: `app/src/test/java/tachiyomi/data/tsuzuki/kitsu/KitsuErrorResilienceTest.kt`
- Modify/Extend: `app/src/test/java/tachiyomi/data/tsuzuki/kitsu/KitsuCatalogProviderTest.kt`

**Interfaces:**
- Produces: Complete proof of spec requirements (offline degradation, 429 rate limit, 500 error resilience, provider-specific scoring).

- [ ] **Step 1: Add error resilience contract test**

Create `app/src/test/java/tachiyomi/data/tsuzuki/kitsu/KitsuErrorResilienceTest.kt`:

```kotlin
package tachiyomi.data.tsuzuki.kitsu

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.service.CatalogProvider
import tachiyomi.domain.tsuzuki.interactor.GetDiscoverFeed
import tachiyomi.domain.tsuzuki.interactor.SearchCatalog

class KitsuErrorResilienceTest {

    @Test
    fun `rate limit failure in provider is exposed as typed RateLimitExceeded without crashing`() = runTest {
        val failingProvider = object : CatalogProvider {
            override val providerId: String = "kitsu"
            override val displayName: String = "Kitsu"
            override suspend fun search(query: CatalogQuery): Result<CatalogPage> =
                Result.failure(CatalogError.RateLimitExceeded(retryAfterSeconds = 120))
            override suspend fun getTrending(offset: Int, limit: Int): Result<CatalogPage> =
                Result.failure(CatalogError.RateLimitExceeded(retryAfterSeconds = 120))
            override suspend fun getPopular(offset: Int, limit: Int): Result<CatalogPage> =
                Result.failure(CatalogError.RateLimitExceeded(retryAfterSeconds = 120))
            override suspend fun getDetails(providerId: String): Result<CatalogItem> =
                Result.failure(CatalogError.RateLimitExceeded(retryAfterSeconds = 120))
        }

        val search = SearchCatalog(failingProvider)
        val result = search.execute("Guts")

        result.isFailure shouldBe true
        val error = result.exceptionOrNull()
        error.shouldBeInstanceOf<CatalogError.RateLimitExceeded>()
        (error as CatalogError.RateLimitExceeded).retryAfterSeconds shouldBe 120
    }

    @Test
    fun `outage during discover does not throw unhandled exception`() = runTest {
        val outageProvider = object : CatalogProvider {
            override val providerId: String = "kitsu"
            override val displayName: String = "Kitsu"
            override suspend fun search(query: CatalogQuery): Result<CatalogPage> =
                Result.failure(CatalogError.ProviderUnavailable("Kitsu is down"))
            override suspend fun getTrending(offset: Int, limit: Int): Result<CatalogPage> =
                Result.failure(CatalogError.ProviderUnavailable("Kitsu is down"))
            override suspend fun getPopular(offset: Int, limit: Int): Result<CatalogPage> =
                Result.failure(CatalogError.ProviderUnavailable("Kitsu is down"))
            override suspend fun getDetails(providerId: String): Result<CatalogItem> =
                Result.failure(CatalogError.ProviderUnavailable("Kitsu is down"))
        }

        val discover = GetDiscoverFeed(outageProvider)
        val feed = discover.execute()

        feed.trending.isFailure shouldBe true
        feed.popular.isFailure shouldBe true
    }
}
```

- [ ] **Step 2: Commit, push, and verify Fast CI**

```bash
git add app/src/test/java/tachiyomi/data/tsuzuki/kitsu/KitsuErrorResilienceTest.kt
git commit -m "test(tsuzuki): verify catalog provider error resilience and graceful degradation"
git push
```

Verify Fast CI runs and all four jobs are green.

---

### Task 8: Minimal title-centric Search / Discover UI

**Goal:** Prove the catalog vertical slice on-device without redesigning the whole Mihon navigation model.

**Files:**
- Create: `app/src/main/java/eu/kanade/presentation/tsuzuki/catalog/CatalogScreen.kt`
- Create: `app/src/main/java/eu/kanade/presentation/tsuzuki/catalog/CatalogItemCard.kt`
- Create: `app/src/main/java/eu/kanade/presentation/tsuzuki/catalog/CatalogItemDetailSheet.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/catalog/CatalogScreen.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/catalog/CatalogScreenModel.kt`
- Modify: the smallest existing Voyager/navigation entry point required to make the screen reachable.
- Test: focused state tests under `app/src/test/java` where logic can be exercised without instrumentation.

**Interfaces:**
- Consumes: `SearchCatalog`, `GetDiscoverFeed`, and provider-neutral `CatalogItem`.
- Produces: one title-centric Search/Discover surface with ephemeral item preview.

**Required UI states:**
```text
Loading
Success
Empty
Degraded
Error
```

**Behavioral constraints:**
- UI must not import Kitsu DTOs or Kitsu networking classes.
- Search and Discover render `CatalogItem`, never source-grouped manga results.
- Opening a catalog preview must not persist a `CanonicalTitle`.
- Do not invoke Source Resolver or search installed reading sources.
- Keep source/extension management intact; do not replace `HomeScreen.TABS` in this milestone.
- Use the smallest existing Voyager/navigation route needed to expose the screen temporarily.

- [ ] **Step 1: Write failing screen-state tests**

Cover search loading -> success, empty results, typed provider failure, partial Discover degradation, and ephemeral item selection.

- [ ] **Step 2: Implement `CatalogScreenModel`**

Follow existing Metro/Voyager screen-model patterns. Expose immutable state and call only `SearchCatalog` / `GetDiscoverFeed`.

- [ ] **Step 3: Implement minimal Compose presentation**

Render a search input, Trending and Popular sections, catalog cards, all required states, and a simple provider-neutral item preview. Reuse existing Mihon presentation components where practical; no broad visual redesign.

- [ ] **Step 4: Add the smallest navigation entry point**

Make the new screen reachable without replacing the complete main navigation. Keep the edit localized and document the temporary route in the PR.

- [ ] **Step 5: Commit, push, and require Fast CI**

```bash
git add app/src/main/java/eu/kanade/presentation/tsuzuki/catalog \
  app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/catalog \
  app/src/test/java
git commit -m "feat(tsuzuki): add minimal catalog search and discover UI"
git push
```

Fast CI is the required acceptance gate. Do not request `[full-ci]` for this task.

---

### Task 9: Final milestone acceptance and APK checkpoint

**Files:**
- Modify only Task 1–8 files if concrete evidence requires a correction.
- Do not start Canonical Library migration, Source Resolver, Canonical Chapters, or Reader integration early.

**Interfaces:**
- Consumes: all Task 1–8 outputs.
- Produces: accepted CatalogProvider + Kitsu + Search / Discover vertical slice.

- [ ] **Step 1: Inspect the milestone diff against base**

```bash
git diff tsuzuki/bootstrap...HEAD --stat
```

Verify:
```text
[x] No provider ID is CanonicalTitle.id
[x] No reading-source logic was added to KitsuCatalogProvider
[x] No Mihon Reader/downloader/extension execution files were modified
[x] Remote CatalogItem models remain ephemeral on Search/Discover
[x] Opening catalog preview does not persist a CanonicalTitle
[x] Materialization persists a Tsuzuki UUID and provider identity mapping
[x] Scores remain provider-specific
[x] Catalog failures remain isolated from local Library/Reader behavior
[x] UI imports provider-neutral domain types rather than Kitsu DTOs
[x] Metro DI resolves the new graph
[x] No Source Resolver or later-phase work slipped into this milestone
```

- [ ] **Step 2: Confirm latest Fast CI is completely green**

Require green Format, Kotlin Compile, Unit Tests, and SQLDelight Migrations jobs.

- [ ] **Step 3: Request exactly one APK/device checkpoint**

```bash
git commit --allow-empty -m "chore: catalog search and discover device checkpoint [apk]"
git push
```

The APK workflow already performs release assembly. Do not also request `[full-ci]` for the same checkpoint.

- [ ] **Step 4: Perform the human/device smoke check**

Verify the catalog screen is reachable; Discover/search render or degrade cleanly; result taps open ephemeral preview; and local Library/Reader remain usable when Kitsu is unavailable.

- [ ] **Step 5: Open/keep the implementation PR in draft for human review**

Create the PR only after the implementation branch exists. Base it on `tsuzuki/bootstrap`. Do not assume a PR number and do not auto-merge.

---
## Acceptance criteria for the whole plan

The Catalog, Kitsu, Search, and Discover milestone is accepted only when all criteria are satisfied:

1. **Provider Independence:** `CatalogProvider` is a clean, provider-neutral abstraction.
2. **Kitsu Integration:** `KitsuCatalogProvider` queries the Kitsu edge API and parses JSON:API data into `CatalogItem` models.
3. **Canonical Identity Ownership:** `MaterializeCanonicalTitleFromCatalog` generates a Tsuzuki UUID for `CanonicalTitle.id` and stores the Kitsu ID as an `ExternalIdentity`. Kitsu ID is NEVER the primary key.
4. **Ephemerality:** Browsing Search, Discover, or Trending results creates zero records in the local database.
5. **Graceful Degradation:** A complete Kitsu outage or HTTP 429 rate limit is captured in typed `CatalogError` and degrades gracefully without breaking local Library or Reader functionality.
6. **Provider-Specific Scores:** Scores remain associated with `"kitsu"` (0–100%) without inventing a synthetic universal score.
7. **Minimal UI Vertical Slice:** Search and basic Discover are reachable in-app, render provider-neutral `CatalogItem` data, and expose Loading/Success/Empty/Degraded/Error behavior without requiring a reading source.
8. **Fast CI Green:** `spotlessCheck`, `:app:compileDebugKotlin`, `testDebugUnitTest`, and `verifySqlDelightMigration` pass.
9. **APK/Device Checkpoint:** exactly one final `[apk]` release build succeeds and the visible Search/Discover flow passes the smoke check; no redundant `[full-ci]` is required for the same checkpoint.
10. **No Scope Creep:** No Source Resolver, Canonical Chapter Engine, Collections Query AST, Google Drive code, or deep navigation redesign is present.

---

## AGY launch prompt

Use this prompt to initiate execution of this plan:

```text
<OBJECTIVE_AND_PERSONA>
You are the AGY orchestrator for Tsuzuki. Coordinate implementation of the Catalog, Kitsu, Search, and Discover milestone. Do not broaden scope. Use Codex as an implementation worker when useful.
</OBJECTIVE_AND_PERSONA>

<CONTEXT>
Repository: jssantogit/mihon
Branch: tsuzuki/mvp-v1-catalog-kitsu-search-discover
Base: tsuzuki/bootstrap
Plan: docs/superpowers/plans/2026-09-18-tsuzuki-mvp-v1-catalog-kitsu-search-discover.md

If the implementation branch does not exist yet, create it from the current `tsuzuki/bootstrap` head before Task 1. Do not target the already-merged canonical-foundation feature branch.

Read, in this order:
1. AGENTS.md
2. .agents/rules/tsuzuki-development.md
3. docs/TSUZUKI-SPEC.md (Sections 2, 6, 23, 28)
4. docs/superpowers/plans/2026-09-18-tsuzuki-mvp-v1-catalog-kitsu-search-discover.md

Development is CI-first. Local Gradle checks are optional; GitHub Fast CI is the required task gate.
</CONTEXT>

<CONSTRAINTS>
- Stay strictly inside the active task scope.
- Enforce: Metadata != Source, CatalogProvider != Reading Source, Canonical identity != Provider identity.
- Remote CatalogItem results are ephemeral; persistence requires explicit user materialization.
- Kitsu failure must never affect local Library, Reader, or downloads.
- Maximum two delta correction cycles per task.
- Fast CI is the normal task gate.
- The final visible UI checkpoint uses one `[apk]`; do not pair it with redundant `[full-ci]`.
- Stop as BLOCKED rather than guessing after repeated failure.
</CONSTRAINTS>

<INSTRUCTIONS>
Based on the repository context above:
1. Inspect the branch and identify the first incomplete task in docs/superpowers/plans/2026-09-18-tsuzuki-mvp-v1-catalog-kitsu-search-discover.md.
2. Define a compact scope contract for that single task.
3. Follow the sequence: EXPLORE -> PLAN -> EXECUTE -> VERIFY -> STOP.
4. Commit and push small reviewable changes.
5. Wait for Fast CI evidence.
6. Report acceptance or issue a delta retry based only on concrete failure logs.
7. Stop after one task reaches acceptance.
</INSTRUCTIONS>

<OUTPUT_FORMAT>
STATUS: DONE | BLOCKED | FAILED
TASK: <number and name>
FILES_CHANGED:
- ...
EVIDENCE:
- test summary
- commit SHA
CI:
- Fast CI run status
ACCEPTANCE:
- criteria check
RISKS:
- concrete risks if any
NEXT:
- next task number
</OUTPUT_FORMAT>

<RECAP>
One task per invocation. CI is authoritative. Preserve scope. Stop after acceptance.
</RECAP>
```

---

## Per-task continuation prompt

```text
<CONTEXT>
Use the same Tsuzuki repository, branch, rules, spec, and CI-first implementation plan already loaded.
The previous task is accepted.
</CONTEXT>

<INSTRUCTIONS>
Based on the preceding repository context, execute only the next incomplete task in docs/superpowers/plans/2026-09-18-tsuzuki-mvp-v1-catalog-kitsu-search-discover.md.
Reconfirm the scope contract before edits, use Codex for implementation when useful, push a small commit, and require Fast CI evidence before acceptance.
Stop after this single task is DONE or BLOCKED.
</INSTRUCTIONS>

<OUTPUT_FORMAT>
STATUS: DONE | BLOCKED | FAILED
TASK:
FILES_CHANGED:
EVIDENCE:
CI:
ACCEPTANCE:
RISKS:
NEXT:
</OUTPUT_FORMAT>

<RECAP>
Do not continue into another task during this invocation.
</RECAP>
```

---

## Next plans after acceptance

Do not start these until this plan is accepted and merged:

```text
1. Canonical Library migration + source preferences + Source Resolver
2. Canonical Chapter Engine + ChapterVariant + coverage/gap detection
3. Reader/progress adapter + per-chapter fallback + tracker-safe canonical progress
```
