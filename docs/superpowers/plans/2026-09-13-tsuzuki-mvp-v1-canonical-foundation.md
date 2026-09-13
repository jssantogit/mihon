# Tsuzuki MVP-V1 Canonical Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce the Tsuzuki-owned canonical identity foundation without replacing Mihon's existing `Manga`, `Chapter`, Reader, downloader, or source models.

**Architecture:** Add a new `tachiyomi.domain.tsuzuki` domain boundary and matching `tachiyomi.data.tsuzuki` persistence boundary. Persist Tsuzuki canonical titles, external identities, Library membership, and logical reading-source mappings in new SQLDelight tables while continuing to reference Mihon operational manga rows through adapters. No existing Mihon table or UI becomes canonical in this plan.

**Tech Stack:** Kotlin, Coroutines/Flow, Metro DI, SQLDelight, JUnit 5, Kotest, Gradle.

**Spec:** `docs/TSUZUKI-SPEC.md`

## Global Constraints

- `Metadata != Source`.
- `Source Manga != CanonicalTitle`.
- `Source Chapter != CanonicalChapter`.
- `Canonical identity != Provider identity`.
- Provider-specific IDs are mappings and never Tsuzuki primary keys.
- A title without catalog metadata must remain usable.
- Existing Mihon `Manga` and `Chapter` models remain operational infrastructure.
- Do not rewrite Reader, downloader, extension execution, source networking, or Mihon chapter synchronization.
- New Tsuzuki code must remain isolated behind domain/repository boundaries to preserve upstream mergeability.
- Local database writes are authoritative before any future cloud synchronization.
- Do not add Kitsu networking, Search UI, source resolution heuristics, canonical chapter logic, Google Drive, or tracker changes in this plan.
- Follow the existing Metro pattern: `@Inject`, `@SingleIn(AppScope::class)`, and `@ContributesBinding(AppScope::class)` for repository implementations.
- Follow the existing SQLDelight migration sequence. The current highest migration is `14.sqm`; this plan introduces `15.sqm`.
- Run the repository's existing CI-equivalent verification before declaring the plan complete:
  - `./gradlew spotlessCheck`
  - `./gradlew testDebugUnitTest`
  - `./gradlew verifySqlDelightMigration`

## Agent execution notes for Codex / AGY

- Treat this document and `docs/TSUZUKI-SPEC.md` as authoritative.
- AGY should orchestrate task order and review evidence; Codex can implement individual tasks.
- Each task is a reviewer gate. Do not combine later tasks into an earlier commit.
- Before editing a file, re-read the current file on the working branch; upstream may have changed after this plan was written.
- If an exact existing signature changed upstream, adapt to the new signature without changing the architecture described here.
- Do not perform unrelated cleanup or rename existing Mihon concepts to Tsuzuki concepts.
- Recommended implementation branch: `tsuzuki/mvp-v1-canonical-foundation`, based on the latest `tsuzuki/bootstrap`.

---

## File structure

### New domain files

- `domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalIdentityState.kt`
  - Canonical identity resolution state.
- `domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalTitle.kt`
  - Minimal persisted Tsuzuki-owned work identity.
- `domain/src/main/java/tachiyomi/domain/tsuzuki/model/ExternalIdentity.kt`
  - Provider ID attached to a canonical title.
- `domain/src/main/java/tachiyomi/domain/tsuzuki/model/LibraryStatus.kt`
  - Tsuzuki Library status enum.
- `domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalLibraryEntry.kt`
  - Canonical Library membership/state.
- `domain/src/main/java/tachiyomi/domain/tsuzuki/model/SourceMappingAvailability.kt`
  - Availability state for a logical reading mapping.
- `domain/src/main/java/tachiyomi/domain/tsuzuki/model/SourceTitleMapping.kt`
  - Logical canonical-title-to-Mihon-source mapping.
- `domain/src/main/java/tachiyomi/domain/tsuzuki/repository/CanonicalTitleRepository.kt`
  - Persistence contract for canonical titles and external identities.
- `domain/src/main/java/tachiyomi/domain/tsuzuki/repository/CanonicalLibraryRepository.kt`
  - Persistence contract for canonical Library entries.
- `domain/src/main/java/tachiyomi/domain/tsuzuki/repository/SourceTitleMappingRepository.kt`
  - Persistence contract for logical source mappings.
- `domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitle.kt`
  - Creates a canonical title only when durable user state requires one.

### New data files

- `data/src/main/sqldelight/tachiyomi/data/tsuzuki_titles.sq`
- `data/src/main/sqldelight/tachiyomi/data/tsuzuki_external_identities.sq`
- `data/src/main/sqldelight/tachiyomi/data/tsuzuki_library_entries.sq`
- `data/src/main/sqldelight/tachiyomi/data/tsuzuki_source_mappings.sq`
- `data/src/main/sqldelight/tachiyomi/migrations/15.sqm`
- `data/src/main/java/tachiyomi/data/tsuzuki/CanonicalTitleRepositoryImpl.kt`
- `data/src/main/java/tachiyomi/data/tsuzuki/CanonicalLibraryRepositoryImpl.kt`
- `data/src/main/java/tachiyomi/data/tsuzuki/SourceTitleMappingRepositoryImpl.kt`

### New tests

- `domain/src/test/java/tachiyomi/domain/tsuzuki/model/CanonicalTitleTest.kt`
- `domain/src/test/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitleTest.kt`

No existing Mihon UI file is modified in this plan.

---

### Task 1: Add the canonical identity value model

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalIdentityState.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalTitle.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/ExternalIdentity.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/model/CanonicalTitleTest.kt`

**Interfaces:**
- Produces:
  - `enum class CanonicalIdentityState`
  - `data class CanonicalTitle`
  - `data class ExternalIdentity`
- Consumes: nothing from Tsuzuki code.

- [ ] **Step 1: Write the failing domain model test**

Create `domain/src/test/java/tachiyomi/domain/tsuzuki/model/CanonicalTitleTest.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CanonicalTitleTest {

    @Test
    fun `source-only title does not require provider identity`() {
        val title = CanonicalTitle(
            id = "title-1",
            displayTitle = "Obscure Manga",
            identityState = CanonicalIdentityState.SOURCE_ONLY,
            createdAt = 100L,
            updatedAt = 100L,
        )

        title.id shouldBe "title-1"
        title.identityState shouldBe CanonicalIdentityState.SOURCE_ONLY
    }

    @Test
    fun `external identity does not replace canonical id`() {
        val title = CanonicalTitle(
            id = "tsuzuki-uuid",
            displayTitle = "Berserk",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 100L,
            updatedAt = 100L,
        )
        val external = ExternalIdentity(
            canonicalTitleId = title.id,
            provider = "kitsu",
            externalId = "123",
            verified = true,
            createdAt = 100L,
        )

        title.id shouldBe "tsuzuki-uuid"
        external.externalId shouldBe "123"
        external.canonicalTitleId shouldBe title.id
    }
}
```

- [ ] **Step 2: Run the focused test and verify the expected compile failure**

Run:

```bash
./gradlew :domain:testDebugUnitTest --tests tachiyomi.domain.tsuzuki.model.CanonicalTitleTest
```

Expected: FAIL because `CanonicalTitle`, `CanonicalIdentityState`, and `ExternalIdentity` do not exist.

- [ ] **Step 3: Add `CanonicalIdentityState`**

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalIdentityState.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.model

enum class CanonicalIdentityState {
    RESOLVED,
    PARTIALLY_RESOLVED,
    SOURCE_ONLY,
}
```

- [ ] **Step 4: Add `CanonicalTitle`**

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalTitle.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.model

data class CanonicalTitle(
    val id: String,
    val displayTitle: String,
    val identityState: CanonicalIdentityState,
    val createdAt: Long,
    val updatedAt: Long,
)
```

Keep this model intentionally minimal. Effective metadata/provenance is added by the catalog plan rather than embedded prematurely into the identity row.

- [ ] **Step 5: Add `ExternalIdentity`**

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/model/ExternalIdentity.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.model

data class ExternalIdentity(
    val canonicalTitleId: String,
    val provider: String,
    val externalId: String,
    val verified: Boolean,
    val createdAt: Long,
)
```

- [ ] **Step 6: Re-run the focused test**

Run:

```bash
./gradlew :domain:testDebugUnitTest --tests tachiyomi.domain.tsuzuki.model.CanonicalTitleTest
```

Expected: PASS.

- [ ] **Step 7: Commit the domain identity model**

```bash
git add   domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalIdentityState.kt   domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalTitle.kt   domain/src/main/java/tachiyomi/domain/tsuzuki/model/ExternalIdentity.kt   domain/src/test/java/tachiyomi/domain/tsuzuki/model/CanonicalTitleTest.kt

git commit -m "feat(tsuzuki): add canonical title identity model"
```

---

### Task 2: Add Library and reading-source mapping models

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/LibraryStatus.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalLibraryEntry.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/SourceMappingAvailability.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/SourceTitleMapping.kt`
- Modify: `domain/src/test/java/tachiyomi/domain/tsuzuki/model/CanonicalTitleTest.kt`

**Interfaces:**
- Consumes: `CanonicalTitle.id: String`
- Produces:
  - `LibraryStatus`
  - `CanonicalLibraryEntry`
  - `SourceMappingAvailability`
  - `SourceTitleMapping`

- [ ] **Step 1: Add failing tests for source-independent Library membership and logical mappings**

Append to `CanonicalTitleTest.kt`:

```kotlin
@Test
fun `library entry does not require source mapping`() {
    val entry = CanonicalLibraryEntry(
        canonicalTitleId = "title-1",
        status = LibraryStatus.PLANNING,
        favorite = false,
        addedAt = 100L,
        updatedAt = 100L,
    )

    entry.canonicalTitleId shouldBe "title-1"
    entry.status shouldBe LibraryStatus.PLANNING
}

@Test
fun `source mapping can exist without local mihon manga id`() {
    val mapping = SourceTitleMapping(
        id = "mapping-1",
        canonicalTitleId = "title-1",
        mihonMangaId = null,
        sourceId = 42L,
        sourceUrl = "/manga/berserk",
        language = "en",
        matchConfidence = 0.99,
        verifiedByUser = false,
        availability = SourceMappingAvailability.AVAILABLE,
        preferredOverride = false,
        createdAt = 100L,
        updatedAt = 100L,
    )

    mapping.mihonMangaId shouldBe null
    mapping.sourceId shouldBe 42L
}
```

- [ ] **Step 2: Run the focused test and confirm it fails for missing models**

Run:

```bash
./gradlew :domain:testDebugUnitTest --tests tachiyomi.domain.tsuzuki.model.CanonicalTitleTest
```

Expected: FAIL because the new Library/mapping types are not defined.

- [ ] **Step 3: Add `LibraryStatus`**

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/model/LibraryStatus.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.model

enum class LibraryStatus {
    READING,
    PLANNING,
    COMPLETED,
    ON_HOLD,
    DROPPED,
}
```

- [ ] **Step 4: Add `CanonicalLibraryEntry`**

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalLibraryEntry.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.model

data class CanonicalLibraryEntry(
    val canonicalTitleId: String,
    val status: LibraryStatus,
    val favorite: Boolean,
    val addedAt: Long,
    val updatedAt: Long,
)
```

Do not add source IDs, tracker IDs, or provider IDs to this model.

- [ ] **Step 5: Add source mapping availability**

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/model/SourceMappingAvailability.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.model

enum class SourceMappingAvailability {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
}
```

- [ ] **Step 6: Add logical source mapping**

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/model/SourceTitleMapping.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.model

data class SourceTitleMapping(
    val id: String,
    val canonicalTitleId: String,
    val mihonMangaId: Long?,
    val sourceId: Long,
    val sourceUrl: String,
    val language: String,
    val matchConfidence: Double?,
    val verifiedByUser: Boolean,
    val availability: SourceMappingAvailability,
    val preferredOverride: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
```

The nullable `mihonMangaId` is deliberate: a Drive-synchronized logical mapping may be known before the corresponding Mihon `Manga` row exists on a device.

- [ ] **Step 7: Re-run the focused test**

Run:

```bash
./gradlew :domain:testDebugUnitTest --tests tachiyomi.domain.tsuzuki.model.CanonicalTitleTest
```

Expected: PASS.

- [ ] **Step 8: Commit the user-state models**

```bash
git add   domain/src/main/java/tachiyomi/domain/tsuzuki/model/LibraryStatus.kt   domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalLibraryEntry.kt   domain/src/main/java/tachiyomi/domain/tsuzuki/model/SourceMappingAvailability.kt   domain/src/main/java/tachiyomi/domain/tsuzuki/model/SourceTitleMapping.kt   domain/src/test/java/tachiyomi/domain/tsuzuki/model/CanonicalTitleTest.kt

git commit -m "feat(tsuzuki): add canonical library and source mapping models"
```

---

### Task 3: Define Tsuzuki repository contracts and title materialization

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/repository/CanonicalTitleRepository.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/repository/CanonicalLibraryRepository.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/repository/SourceTitleMappingRepository.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitle.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitleTest.kt`

**Interfaces:**
- Consumes: models from Tasks 1-2.
- Produces:
  - canonical persistence contracts;
  - `MaterializeCanonicalTitle.fromCatalog(...)`;
  - `MaterializeCanonicalTitle.fromSource(...)`.

- [ ] **Step 1: Create repository contracts**

Create `CanonicalTitleRepository.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity

interface CanonicalTitleRepository {
    suspend fun getById(id: String): CanonicalTitle?
    fun getByIdAsFlow(id: String): Flow<CanonicalTitle?>
    suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle?
    suspend fun insert(title: CanonicalTitle)
    suspend fun addExternalIdentity(identity: ExternalIdentity)
}
```

Create `CanonicalLibraryRepository.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry

interface CanonicalLibraryRepository {
    suspend fun get(canonicalTitleId: String): CanonicalLibraryEntry?
    fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>>
    suspend fun upsert(entry: CanonicalLibraryEntry)
    suspend fun remove(canonicalTitleId: String)
}
```

Create `SourceTitleMappingRepository.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping

interface SourceTitleMappingRepository {
    suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping>
    fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>>
    suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping?
    suspend fun upsert(mapping: SourceTitleMapping)
}
```

- [ ] **Step 2: Write the materialization tests before the interactor**

Create `domain/src/test/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitleTest.kt` with in-memory fakes:

```kotlin
package tachiyomi.domain.tsuzuki.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class MaterializeCanonicalTitleTest {

    @Test
    fun `catalog materialization reuses title with same provider identity`() = runTest {
        val repository = FakeCanonicalTitleRepository()
        val interactor = MaterializeCanonicalTitle(
            repository = repository,
            idFactory = { "generated-id" },
            clock = { 100L },
        )

        val first = interactor.fromCatalog(
            displayTitle = "Berserk",
            provider = "kitsu",
            externalId = "123",
        )
        val second = interactor.fromCatalog(
            displayTitle = "Berserk",
            provider = "kitsu",
            externalId = "123",
        )

        first.id shouldBe "generated-id"
        second.id shouldBe first.id
        repository.titles.size shouldBe 1
    }

    @Test
    fun `source materialization creates source-only identity without provider id`() = runTest {
        val repository = FakeCanonicalTitleRepository()
        val interactor = MaterializeCanonicalTitle(
            repository = repository,
            idFactory = { "source-id" },
            clock = { 100L },
        )

        val title = interactor.fromSource("Obscure Manga")

        title.id shouldBe "source-id"
        title.identityState shouldBe CanonicalIdentityState.SOURCE_ONLY
        repository.identities.size shouldBe 0
    }

    private class FakeCanonicalTitleRepository : CanonicalTitleRepository {
        val titles = mutableMapOf<String, CanonicalTitle>()
        val identities = mutableListOf<ExternalIdentity>()
        private val flow = MutableStateFlow<CanonicalTitle?>(null)

        override suspend fun getById(id: String): CanonicalTitle? = titles[id]

        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = flow

        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? {
            val titleId = identities
                .firstOrNull { it.provider == provider && it.externalId == externalId }
                ?.canonicalTitleId
            return titleId?.let(titles::get)
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

- [ ] **Step 3: Run the focused test and verify it fails because the interactor is missing**

Run:

```bash
./gradlew :domain:testDebugUnitTest --tests tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleTest
```

Expected: FAIL because `MaterializeCanonicalTitle` does not exist.

- [ ] **Step 4: Implement title materialization**

Create `domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitle.kt`:

```kotlin
package tachiyomi.domain.tsuzuki.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import java.util.UUID
import kotlin.time.Clock

class MaterializeCanonicalTitle internal constructor(
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

    suspend fun fromCatalog(
        displayTitle: String,
        provider: String,
        externalId: String,
    ): CanonicalTitle {
        repository.getByExternalIdentity(provider, externalId)?.let { return it }

        val now = clock()
        val title = CanonicalTitle(
            id = idFactory(),
            displayTitle = displayTitle,
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = now,
            updatedAt = now,
        )
        repository.insert(title)
        repository.addExternalIdentity(
            ExternalIdentity(
                canonicalTitleId = title.id,
                provider = provider,
                externalId = externalId,
                verified = true,
                createdAt = now,
            ),
        )
        return title
    }

    suspend fun fromSource(displayTitle: String): CanonicalTitle {
        val now = clock()
        return CanonicalTitle(
            id = idFactory(),
            displayTitle = displayTitle,
            identityState = CanonicalIdentityState.SOURCE_ONLY,
            createdAt = now,
            updatedAt = now,
        ).also { repository.insert(it) }
    }
}
```

- [ ] **Step 5: Re-run the materialization tests**

Run:

```bash
./gradlew :domain:testDebugUnitTest --tests tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleTest
```

Expected: PASS.

- [ ] **Step 6: Run all Tsuzuki domain tests**

Run:

```bash
./gradlew :domain:testDebugUnitTest --tests 'tachiyomi.domain.tsuzuki.*'
```

Expected: PASS.

- [ ] **Step 7: Commit repository contracts and materialization**

```bash
git add   domain/src/main/java/tachiyomi/domain/tsuzuki/repository   domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitle.kt   domain/src/test/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitleTest.kt

git commit -m "feat(tsuzuki): add canonical repositories and materialization"
```

---

### Task 4: Add SQLDelight schema and migration 15

**Files:**
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_titles.sq`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_external_identities.sq`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_library_entries.sq`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_source_mappings.sq`
- Create: `data/src/main/sqldelight/tachiyomi/migrations/15.sqm`

**Interfaces:**
- Consumes: domain field definitions from Tasks 1-2.
- Produces: generated SQLDelight query APIs used by Task 5.

- [ ] **Step 1: Add the canonical title table and queries**

Create `tsuzuki_titles.sq`:

```sql
CREATE TABLE tsuzuki_titles(
    id TEXT NOT NULL PRIMARY KEY,
    display_title TEXT NOT NULL,
    identity_state TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

getTsuzukiTitleById:
SELECT *
FROM tsuzuki_titles
WHERE id = :id;

insertTsuzukiTitle:
INSERT INTO tsuzuki_titles(
    id,
    display_title,
    identity_state,
    created_at,
    updated_at
)
VALUES (
    :id,
    :displayTitle,
    :identityState,
    :createdAt,
    :updatedAt
);
```

- [ ] **Step 2: Add external identity storage**

Create `tsuzuki_external_identities.sq`:

```sql
import kotlin.Boolean;

CREATE TABLE tsuzuki_external_identities(
    canonical_title_id TEXT NOT NULL,
    provider TEXT NOT NULL,
    external_id TEXT NOT NULL,
    verified INTEGER AS kotlin.Boolean NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY(provider, external_id),
    FOREIGN KEY(canonical_title_id) REFERENCES tsuzuki_titles(id) ON DELETE CASCADE
);

CREATE INDEX tsuzuki_external_identity_title_index
ON tsuzuki_external_identities(canonical_title_id);

getTsuzukiTitleIdByExternalIdentity:
SELECT canonical_title_id
FROM tsuzuki_external_identities
WHERE provider = :provider
AND external_id = :externalId;

insertTsuzukiExternalIdentity:
INSERT INTO tsuzuki_external_identities(
    canonical_title_id,
    provider,
    external_id,
    verified,
    created_at
)
VALUES (
    :canonicalTitleId,
    :provider,
    :externalId,
    :verified,
    :createdAt
);
```

- [ ] **Step 3: Add canonical Library storage**

Create `tsuzuki_library_entries.sq`:

```sql
import kotlin.Boolean;

CREATE TABLE tsuzuki_library_entries(
    canonical_title_id TEXT NOT NULL PRIMARY KEY,
    status TEXT NOT NULL,
    favorite INTEGER AS kotlin.Boolean NOT NULL,
    added_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY(canonical_title_id) REFERENCES tsuzuki_titles(id) ON DELETE CASCADE
);

getTsuzukiLibraryEntry:
SELECT *
FROM tsuzuki_library_entries
WHERE canonical_title_id = :canonicalTitleId;

getAllTsuzukiLibraryEntries:
SELECT *
FROM tsuzuki_library_entries
ORDER BY added_at DESC;

upsertTsuzukiLibraryEntry:
INSERT INTO tsuzuki_library_entries(
    canonical_title_id,
    status,
    favorite,
    added_at,
    updated_at
)
VALUES (
    :canonicalTitleId,
    :status,
    :favorite,
    :addedAt,
    :updatedAt
)
ON CONFLICT(canonical_title_id) DO UPDATE SET
    status = excluded.status,
    favorite = excluded.favorite,
    updated_at = excluded.updated_at;

deleteTsuzukiLibraryEntry:
DELETE FROM tsuzuki_library_entries
WHERE canonical_title_id = :canonicalTitleId;
```

- [ ] **Step 4: Add logical source mapping storage**

Create `tsuzuki_source_mappings.sq`:

```sql
import kotlin.Boolean;

CREATE TABLE tsuzuki_source_mappings(
    id TEXT NOT NULL PRIMARY KEY,
    canonical_title_id TEXT NOT NULL,
    mihon_manga_id INTEGER,
    source_id INTEGER NOT NULL,
    source_url TEXT NOT NULL,
    language TEXT NOT NULL,
    match_confidence REAL,
    verified_by_user INTEGER AS kotlin.Boolean NOT NULL,
    availability TEXT NOT NULL,
    preferred_override INTEGER AS kotlin.Boolean NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY(canonical_title_id) REFERENCES tsuzuki_titles(id) ON DELETE CASCADE,
    UNIQUE(canonical_title_id, source_id, source_url)
);

CREATE INDEX tsuzuki_source_mapping_title_index
ON tsuzuki_source_mappings(canonical_title_id);

getTsuzukiSourceMappingsByTitle:
SELECT *
FROM tsuzuki_source_mappings
WHERE canonical_title_id = :canonicalTitleId
ORDER BY created_at ASC;

getTsuzukiSourceMapping:
SELECT *
FROM tsuzuki_source_mappings
WHERE source_id = :sourceId
AND source_url = :sourceUrl
LIMIT 1;

upsertTsuzukiSourceMapping:
INSERT INTO tsuzuki_source_mappings(
    id,
    canonical_title_id,
    mihon_manga_id,
    source_id,
    source_url,
    language,
    match_confidence,
    verified_by_user,
    availability,
    preferred_override,
    created_at,
    updated_at
)
VALUES (
    :id,
    :canonicalTitleId,
    :mihonMangaId,
    :sourceId,
    :sourceUrl,
    :language,
    :matchConfidence,
    :verifiedByUser,
    :availability,
    :preferredOverride,
    :createdAt,
    :updatedAt
)
ON CONFLICT(canonical_title_id, source_id, source_url) DO UPDATE SET
    mihon_manga_id = excluded.mihon_manga_id,
    language = excluded.language,
    match_confidence = excluded.match_confidence,
    verified_by_user = excluded.verified_by_user,
    availability = excluded.availability,
    preferred_override = excluded.preferred_override,
    updated_at = excluded.updated_at;
```

- [ ] **Step 5: Add migration 15 with exactly the same schema**

Create `data/src/main/sqldelight/tachiyomi/migrations/15.sqm`.

The migration must contain the four `CREATE TABLE` statements and indexes from the new `.sq` files, but not the named queries.

Use the same column names, constraints, primary keys, foreign keys, and indexes verbatim so SQLDelight schema verification can compare the migrated schema against the fresh schema.

- [ ] **Step 6: Verify SQLDelight schema generation and migration**

Run:

```bash
./gradlew :data:generateDebugDatabaseInterface
./gradlew verifySqlDelightMigration
```

Expected: both commands exit 0.

If the exact generated-interface task name differs on the current upstream revision, run:

```bash
./gradlew :data:tasks --all | grep -i Database
```

then execute the matching SQLDelight generation task before `verifySqlDelightMigration`.

- [ ] **Step 7: Commit the schema**

```bash
git add   data/src/main/sqldelight/tachiyomi/data/tsuzuki_titles.sq   data/src/main/sqldelight/tachiyomi/data/tsuzuki_external_identities.sq   data/src/main/sqldelight/tachiyomi/data/tsuzuki_library_entries.sq   data/src/main/sqldelight/tachiyomi/data/tsuzuki_source_mappings.sq   data/src/main/sqldelight/tachiyomi/migrations/15.sqm

git commit -m "feat(tsuzuki): add canonical persistence schema"
```

---

### Task 5: Implement data repositories with Metro bindings

**Files:**
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/CanonicalTitleRepositoryImpl.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/CanonicalLibraryRepositoryImpl.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/SourceTitleMappingRepositoryImpl.kt`

**Interfaces:**
- Consumes:
  - `CanonicalTitleRepository`
  - `CanonicalLibraryRepository`
  - `SourceTitleMappingRepository`
  - generated SQLDelight queries from Task 4.
- Produces Metro-bound AppScope implementations.

- [ ] **Step 1: Implement canonical title persistence**

Create `CanonicalTitleRepositoryImpl.kt`:

```kotlin
package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToOneOrNull
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CanonicalTitleRepositoryImpl(
    private val database: Database,
) : CanonicalTitleRepository {

    override suspend fun getById(id: String): CanonicalTitle? {
        return database.tsuzuki_titlesQueries
            .getTsuzukiTitleById(id, ::mapTitle)
            .awaitAsOneOrNull()
    }

    override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> {
        return database.tsuzuki_titlesQueries
            .getTsuzukiTitleById(id, ::mapTitle)
            .subscribeToOneOrNull()
    }

    override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? {
        val titleId = database.tsuzuki_external_identitiesQueries
            .getTsuzukiTitleIdByExternalIdentity(provider, externalId)
            .awaitAsOneOrNull()
            ?: return null
        return getById(titleId)
    }

    override suspend fun insert(title: CanonicalTitle) {
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = title.id,
            displayTitle = title.displayTitle,
            identityState = title.identityState.name,
            createdAt = title.createdAt,
            updatedAt = title.updatedAt,
        )
    }

    override suspend fun addExternalIdentity(identity: ExternalIdentity) {
        database.tsuzuki_external_identitiesQueries.insertTsuzukiExternalIdentity(
            canonicalTitleId = identity.canonicalTitleId,
            provider = identity.provider,
            externalId = identity.externalId,
            verified = identity.verified,
            createdAt = identity.createdAt,
        )
    }

    private fun mapTitle(
        id: String,
        displayTitle: String,
        identityState: String,
        createdAt: Long,
        updatedAt: Long,
    ) = CanonicalTitle(
        id = id,
        displayTitle = displayTitle,
        identityState = CanonicalIdentityState.valueOf(identityState),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
```

- [ ] **Step 2: Implement canonical Library persistence**

Create `CanonicalLibraryRepositoryImpl.kt`:

```kotlin
package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CanonicalLibraryRepositoryImpl(
    private val database: Database,
) : CanonicalLibraryRepository {

    override suspend fun get(canonicalTitleId: String): CanonicalLibraryEntry? {
        return database.tsuzuki_library_entriesQueries
            .getTsuzukiLibraryEntry(canonicalTitleId, ::mapEntry)
            .awaitAsOneOrNull()
    }

    override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> {
        return database.tsuzuki_library_entriesQueries
            .getAllTsuzukiLibraryEntries(::mapEntry)
            .subscribeToList()
    }

    override suspend fun upsert(entry: CanonicalLibraryEntry) {
        database.tsuzuki_library_entriesQueries.upsertTsuzukiLibraryEntry(
            canonicalTitleId = entry.canonicalTitleId,
            status = entry.status.name,
            favorite = entry.favorite,
            addedAt = entry.addedAt,
            updatedAt = entry.updatedAt,
        )
    }

    override suspend fun remove(canonicalTitleId: String) {
        database.tsuzuki_library_entriesQueries.deleteTsuzukiLibraryEntry(canonicalTitleId)
    }

    private fun mapEntry(
        canonicalTitleId: String,
        status: String,
        favorite: Boolean,
        addedAt: Long,
        updatedAt: Long,
    ) = CanonicalLibraryEntry(
        canonicalTitleId = canonicalTitleId,
        status = LibraryStatus.valueOf(status),
        favorite = favorite,
        addedAt = addedAt,
        updatedAt = updatedAt,
    )
}
```

After compilation, remove any imports such as `awaitAsList` that the file does not use.

- [ ] **Step 3: Implement source mapping persistence**

Create `SourceTitleMappingRepositoryImpl.kt`:

```kotlin
package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SourceTitleMappingRepositoryImpl(
    private val database: Database,
) : SourceTitleMappingRepository {

    override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> {
        return database.tsuzuki_source_mappingsQueries
            .getTsuzukiSourceMappingsByTitle(canonicalTitleId, ::mapMapping)
            .awaitAsList()
    }

    override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> {
        return database.tsuzuki_source_mappingsQueries
            .getTsuzukiSourceMappingsByTitle(canonicalTitleId, ::mapMapping)
            .subscribeToList()
    }

    override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? {
        return database.tsuzuki_source_mappingsQueries
            .getTsuzukiSourceMapping(sourceId, sourceUrl, ::mapMapping)
            .awaitAsOneOrNull()
    }

    override suspend fun upsert(mapping: SourceTitleMapping) {
        database.tsuzuki_source_mappingsQueries.upsertTsuzukiSourceMapping(
            id = mapping.id,
            canonicalTitleId = mapping.canonicalTitleId,
            mihonMangaId = mapping.mihonMangaId,
            sourceId = mapping.sourceId,
            sourceUrl = mapping.sourceUrl,
            language = mapping.language,
            matchConfidence = mapping.matchConfidence,
            verifiedByUser = mapping.verifiedByUser,
            availability = mapping.availability.name,
            preferredOverride = mapping.preferredOverride,
            createdAt = mapping.createdAt,
            updatedAt = mapping.updatedAt,
        )
    }

    private fun mapMapping(
        id: String,
        canonicalTitleId: String,
        mihonMangaId: Long?,
        sourceId: Long,
        sourceUrl: String,
        language: String,
        matchConfidence: Double?,
        verifiedByUser: Boolean,
        availability: String,
        preferredOverride: Boolean,
        createdAt: Long,
        updatedAt: Long,
    ) = SourceTitleMapping(
        id = id,
        canonicalTitleId = canonicalTitleId,
        mihonMangaId = mihonMangaId,
        sourceId = sourceId,
        sourceUrl = sourceUrl,
        language = language,
        matchConfidence = matchConfidence,
        verifiedByUser = verifiedByUser,
        availability = SourceMappingAvailability.valueOf(availability),
        preferredOverride = preferredOverride,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
```

- [ ] **Step 4: Compile the domain and data modules**

Run:

```bash
./gradlew :domain:compileDebugKotlin :data:compileDebugKotlin
```

Expected: PASS.

If generated SQLDelight mapper signatures differ in parameter names but not semantics, adjust the repository mapper function signatures to the generated order/types. Do not alter domain ownership to accommodate generated naming.

- [ ] **Step 5: Run Tsuzuki domain tests and SQLDelight migration verification**

Run:

```bash
./gradlew :domain:testDebugUnitTest --tests 'tachiyomi.domain.tsuzuki.*'
./gradlew verifySqlDelightMigration
```

Expected: PASS.

- [ ] **Step 6: Commit repository implementations**

```bash
git add data/src/main/java/tachiyomi/data/tsuzuki

git commit -m "feat(tsuzuki): persist canonical identity state"
```

---

### Task 6: Prove the foundation against repository-wide verification

**Files:**
- Modify only files from Tasks 1-5 if verification exposes formatting, generated-signature, or migration consistency issues.
- Do not add UI, Kitsu, source-resolution, chapter-resolution, or sync functionality.

**Interfaces:**
- Consumes: all outputs from Tasks 1-5.
- Produces: a verified canonical foundation suitable for the next implementation plan.

- [ ] **Step 1: Run formatting checks**

```bash
./gradlew spotlessCheck
```

Expected: PASS.

If formatting fails only in new files, run:

```bash
./gradlew spotlessApply
```

then inspect the diff and re-run `spotlessCheck`.

- [ ] **Step 2: Run all unit tests**

```bash
./gradlew testDebugUnitTest
```

Expected: PASS with zero failed tests.

- [ ] **Step 3: Re-run SQLDelight migration verification**

```bash
./gradlew verifySqlDelightMigration
```

Expected: PASS.

- [ ] **Step 4: Compile a release APK to catch graph/codegen integration failures**

```bash
./gradlew assembleRelease -Pinclude-telemetry -Penable-updater
```

Expected: PASS.

This is important because Metro and SQLDelight generated code can expose integration failures that isolated domain tests do not.

- [ ] **Step 5: Inspect the final diff for architectural leakage**

Run:

```bash
git diff tsuzuki/bootstrap...HEAD --   domain/src/main/java/tachiyomi/domain/tsuzuki   data/src/main/java/tachiyomi/data/tsuzuki   data/src/main/sqldelight/tachiyomi/data   data/src/main/sqldelight/tachiyomi/migrations/15.sqm
```

Verify all of the following manually:

- no existing Mihon `Manga` field was repurposed as canonical identity;
- no provider ID is used as `CanonicalTitle.id`;
- Library entries contain no source dependency;
- logical source mappings permit `mihonMangaId = null`;
- no UI/source/reader code changed;
- migration 15 matches the fresh SQLDelight schema;
- no Google Drive/tracker behavior was introduced.

- [ ] **Step 6: Commit any verification-only fixes**

If verification required changes:

```bash
git add domain data
git commit -m "fix(tsuzuki): verify canonical foundation integration"
```

If no changes were needed, do not create an empty commit.

---

## Completion criteria

This plan is complete only when:

1. Tsuzuki has its own stable canonical-title ID type represented by a `String` value that is not a provider ID.
2. Source-only titles are valid domain entities.
3. Provider identities are stored separately from canonical identity.
4. Canonical Library membership exists without requiring a reading source.
5. Logical source mappings exist independently from a device-local Mihon manga row.
6. New persistence lives in Tsuzuki-owned SQLDelight tables.
7. Existing Mihon `mangas` and `chapters` tables/models are not redefined as canonical entities.
8. Metro can resolve the new repository interfaces.
9. `spotlessCheck`, `testDebugUnitTest`, `verifySqlDelightMigration`, and `assembleRelease -Pinclude-telemetry -Penable-updater` all pass.
10. No catalog networking, UI replacement, Source Resolver heuristic, Canonical Chapter Engine, Drive sync, or tracker behavior has leaked into this foundation plan.

## Next implementation plans

The remaining MVP-V1 work should be planned and executed in dependency order as separate agent-sized documents:

1. **Catalog + Kitsu + unified Search/Discover**
2. **Canonical Library migration + source preferences + Source Resolver**
3. **Canonical Chapter Engine + ChapterVariant + coverage/gap detection**
4. **Reader/progress adapter + per-chapter fallback + tracker-safe canonical progress**

Each subsequent plan must consume the public interfaces created here rather than bypassing them.
