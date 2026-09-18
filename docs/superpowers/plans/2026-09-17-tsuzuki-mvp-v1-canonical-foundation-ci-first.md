# Tsuzuki MVP-V1 Canonical Foundation — CI-First Agent Execution Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the Tsuzuki canonical-identity foundation using AGY as orchestrator/reviewer, Codex as an implementation worker, and GitHub Actions as the authoritative Gradle/Kotlin verification environment.

**Architecture:** Tsuzuki adds a new `tachiyomi.domain.tsuzuki` domain boundary and matching `tachiyomi.data.tsuzuki` persistence boundary above Mihon's existing operational `Manga`, `Chapter`, Reader, downloader, and source infrastructure. Development is CI-first: constrained PCs and Android/Termux devices edit, review, commit, and push; GitHub Actions performs the heavy Kotlin, unit-test, SQLDelight, and release-build work.

**Tech Stack:** Kotlin, Coroutines/Flow, Metro DI, SQLDelight, JUnit 5, Kotest, Gradle, GitHub Actions, AGY/Antigravity, Codex.

**Spec:** `docs/TSUZUKI-SPEC.md`

**Development workflow:** `docs/TSUZUKI-DEVELOPMENT.md`

**Supersedes for execution:** `docs/superpowers/plans/2026-09-13-tsuzuki-mvp-v1-canonical-foundation.md`

## Current branch state

Implementation branch:

```text
tsuzuki/mvp-v1-canonical-foundation
```

Draft PR:

```text
#2 — Tsuzuki MVP-V1: Canonical foundation
base: tsuzuki/bootstrap
```

Already present on the branch:

```text
domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalIdentityState.kt
domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalTitle.kt
domain/src/main/java/tachiyomi/domain/tsuzuki/model/ExternalIdentity.kt
domain/src/test/java/tachiyomi/domain/tsuzuki/model/CanonicalTitleTest.kt
.github/workflows/ci.yml
.github/workflows/build.yml
docs/TSUZUKI-DEVELOPMENT.md
```

Do not recreate these files unless a task below explicitly modifies them.

---

## Google / Gemini / Antigravity prompting contract

This execution plan follows the current Google guidance for Gemini/Antigravity and the Tsuzuki agent architecture.

### 1. Keep durable context outside the task prompt

Do not paste the whole spec into every prompt.

Stable project rules live in:

```text
AGENTS.md
.agents/rules/tsuzuki-development.md
docs/TSUZUKI-SPEC.md
docs/TSUZUKI-DEVELOPMENT.md
this implementation plan
```

Task prompts should contain only dynamic context and the current task.

### 2. Use concise, direct instructions

Gemini 3.x reasoning models should receive precise instructions rather than a giant adversarial superprompt.

Use this task order:

```text
EXPLORE
  ↓
PLAN
  ↓
EXECUTE
  ↓
VERIFY
  ↓
STOP
```

Do not add extra phases unless evidence requires them.

### 3. Put repository context before the final task instruction

For codebase work, load/read the relevant files first, then give the concrete task instruction. The final task prompt should be anchored with language equivalent to:

```text
Based on the repository context and constraints above, execute only Task N.
```

### 4. Use explicit prompt components

Each AGY task handoff uses these semantic sections:

```text
<OBJECTIVE_AND_PERSONA>
<CONTEXT>
<CONSTRAINTS>
<INSTRUCTIONS>
<OUTPUT_FORMAT>
<RECAP>
```

The sections are structure, not permission to make the prompt verbose.

### 5. Narrow scope and termination criteria

Every task must define:

```text
allowed paths
forbidden paths
acceptance criteria
CI gate
retry limit
stop conditions
```

A task is finished when its stated acceptance evidence exists. Do not continue exploring after acceptance.

### 6. Rules and hooks enforce what prompts should not have to repeat

AGY should rely on persistent rules/permissions for architectural invariants and write boundaries. The task prompt carries only current scope.

### 7. No chain-of-thought requests

Do not ask workers to expose internal reasoning. Require evidence instead:

```text
files changed
commands or CI run
pass/fail status
failure excerpt
acceptance criteria
next action
```

### 8. Bounded investigation

For each task:

- perform one consolidated discovery pass before editing;
- batch related reads/searches when possible;
- do not repeatedly reread unchanged files;
- do not launch side investigations unrelated to acceptance;
- maximum two correction cycles after CI failure;
- if two correction cycles fail for different root causes, stop as `BLOCKED` and request review.

### 9. Delta retry only

A retry must state:

```text
FAILED_OR_MISSING
NEW_EVIDENCE
REQUIRED_CORRECTION
PRESERVE
REMAINING_ACCEPTANCE
```

Do not restart the task from scratch unless the plan itself is invalid.

### 10. CI evidence outranks agent confidence

Never report a task as accepted because code "looks right" or tests "should pass".

Acceptance hierarchy:

```text
focused local check (optional)
        ↓
Fast CI (required task gate)
        ↓
Full Verify (only when integration risk justifies it)
        ↓
APK Build (only when device validation is useful)
```

---

## AGY / Codex role contract

### AGY orchestrator

AGY owns:

- reading the spec, development workflow, and this plan;
- classifying current task scope;
- writing the scope contract;
- delegating implementation to Codex when useful;
- inspecting the resulting diff;
- waiting for GitHub Actions evidence;
- accepting, retrying with a delta, or blocking the task.

AGY must not broaden scope just because it discovers adjacent cleanup.

### Codex worker

Codex owns:

- implementation inside allowed paths;
- tests required by the task;
- small mechanical corrections demonstrated by CI;
- returning a compact evidence summary.

Codex does not decide product architecture, add future features, or modify unrelated Mihon infrastructure.

### State machine

Use only these task states:

```text
INTAKE
EXPLORE
PLANNED
EXECUTING
CI_WAIT
ACCEPTANCE
DONE
BLOCKED
```

State transitions are fail-closed:

```text
INTAKE -> EXPLORE -> PLANNED -> EXECUTING -> CI_WAIT -> ACCEPTANCE -> DONE
                                                        |
                                                        +-> EXECUTING (delta retry)
                                                        +-> BLOCKED
```

---

## Global architectural constraints

- `Metadata != Source`.
- `Source Manga != CanonicalTitle`.
- `Source Chapter != CanonicalChapter`.
- `Canonical identity != Provider identity`.
- Provider IDs are mappings and never Tsuzuki primary keys.
- A title without catalog metadata remains valid.
- Existing Mihon `Manga` and `Chapter` models remain operational infrastructure.
- Do not rewrite Reader, downloader, extension execution, source networking, or Mihon chapter synchronization.
- New Tsuzuki code remains isolated behind domain/repository boundaries.
- Local user state is authoritative before future cloud sync.
- Do not add Kitsu networking in this plan.
- Do not add Search/Discover UI in this plan.
- Do not add Source Resolver heuristics in this plan.
- Do not add the Canonical Chapter Engine in this plan.
- Do not add Google Drive in this plan.
- Do not change tracker behavior in this plan.
- Do not redesign existing Mihon UI in this plan.
- Follow existing Metro binding conventions.
- The current highest SQLDelight migration before Tsuzuki persistence is `14.sqm`; this plan introduces `15.sqm`.

---

## CI contract

### Fast CI — required after every implementation gate

`.github/workflows/ci.yml` runs:

```text
Format
  ./gradlew spotlessCheck

Kotlin Compile
  ./gradlew :app:compileDebugKotlin

Unit Tests
  ./gradlew testDebugUnitTest

SQLDelight Migrations
  ./gradlew verifySqlDelightMigration
```

The jobs run independently.

Do not require Android Studio, Android SDK management, or a full Gradle build on the developer phone/PC.

### Waiting for CI

Prefer a single wait operation over repeated polling.

If `gh` is available:

```bash
gh run list --workflow "Fast CI" --branch tsuzuki/mvp-v1-canonical-foundation --limit 3
gh run watch <RUN_ID> --exit-status
```

If `gh` is unavailable, use the GitHub Actions UI or an available GitHub integration to inspect the corresponding run.

### Full Build & APK

Use only at milestone/checkpoint acceptance:

```text
non-draft PR
or
commit message containing [full-ci]
or
manual workflow_dispatch
```

Do not request a full release build after every small task.

---

## Output contract for every AGY task

Return exactly these sections to the human/reviewer:

```text
STATUS: DONE | BLOCKED | FAILED
TASK: <number and name>
FILES_CHANGED:
- ...
EVIDENCE:
- test added/changed
- commit SHA(s)
CI:
- Fast CI run ID/status
- failing job + concise failure if red
ACCEPTANCE:
- criterion: PASS/FAIL
RISKS:
- only concrete remaining risks
NEXT:
- next task number, or HUMAN REVIEW if blocked
```

Do not provide hidden reasoning or a long narrative.

---

## File structure produced by this plan

### Persistent agent instructions

- Create: `AGENTS.md`
- Create: `.agents/rules/tsuzuki-development.md`

### Domain

- Existing: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalIdentityState.kt`
- Existing: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalTitle.kt`
- Existing: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/ExternalIdentity.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/LibraryStatus.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalLibraryEntry.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/SourceMappingAvailability.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/SourceTitleMapping.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/repository/CanonicalTitleRepository.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/repository/CanonicalLibraryRepository.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/repository/SourceTitleMappingRepository.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitle.kt`

### Data

- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_titles.sq`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_external_identities.sq`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_library_entries.sq`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_source_mappings.sq`
- Create: `data/src/main/sqldelight/tachiyomi/migrations/15.sqm`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/CanonicalTitleRepositoryImpl.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/CanonicalLibraryRepositoryImpl.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/SourceTitleMappingRepositoryImpl.kt`

### Tests

- Existing: `domain/src/test/java/tachiyomi/domain/tsuzuki/model/CanonicalTitleTest.kt`
- Create: `domain/src/test/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitleTest.kt`

---

### Task 0: Anchor Tsuzuki rules for AGY and Codex

**Files:**
- Create: `AGENTS.md`
- Create: `.agents/rules/tsuzuki-development.md`

**Interfaces:**
- Consumes: `docs/TSUZUKI-SPEC.md`, `docs/TSUZUKI-DEVELOPMENT.md`, this plan.
- Produces: persistent repo-level instructions read by AGY/Codex before task prompts.

- [ ] **Step 1: Create the concise root agent entrypoint**

Create `AGENTS.md`:

```markdown
# Tsuzuki agent instructions

Read these files before changing Tsuzuki code:

1. `docs/TSUZUKI-SPEC.md`
2. `docs/TSUZUKI-DEVELOPMENT.md`
3. the active file under `docs/superpowers/plans/`
4. `.agents/rules/tsuzuki-development.md`

Core invariants:

- Metadata != Source.
- Source Manga != CanonicalTitle.
- Source Chapter != CanonicalChapter.
- Canonical identity != Provider identity.
- Mihon remains the operational reading mechanism.
- Tsuzuki-owned behavior belongs behind dedicated domain/data boundaries where practical.

Development is CI-first. Local full Gradle verification is optional; GitHub Fast CI is the required task gate.

Do not broaden the active task scope. Do not implement future plan phases early.
```

- [ ] **Step 2: Create the durable Antigravity rule**

Create `.agents/rules/tsuzuki-development.md`:

```markdown
# Tsuzuki development rule

## Goal

Implement Tsuzuki incrementally while preserving Mihon upstream compatibility and using GitHub Actions as the authoritative Gradle/Kotlin verification environment.

## Execution

For each task:

1. Read the active spec, development workflow, and implementation-plan task.
2. Perform one consolidated discovery pass.
3. State the task scope: allowed paths, forbidden paths, acceptance, CI gate, stop conditions.
4. Delegate implementation when useful.
5. Review the diff before pushing.
6. Push a small commit.
7. Wait for Fast CI evidence.
8. Accept only if the relevant CI jobs are green.
9. On failure, issue a delta retry using only the demonstrated failure evidence.
10. Stop after at most two correction cycles and report BLOCKED if acceptance still fails.

## Constraints

- Do not turn a provider ID into CanonicalTitle identity.
- Do not turn a Mihon Source manga into CanonicalTitle identity.
- Do not rewrite Reader, downloader, extension execution, or source networking unless a later approved plan explicitly requires it.
- Do not add Kitsu, Search UI, Source Resolver, Canonical Chapter Engine, Drive sync, or tracker changes during the canonical-foundation plan.
- Do not perform unrelated cleanup.
- Do not claim verification without CI or an actually executed local check.
- Do not repeatedly poll CI; use a single run wait/watch when possible.

## Evidence output

Return:

- STATUS
- TASK
- FILES_CHANGED
- EVIDENCE
- CI
- ACCEPTANCE
- RISKS
- NEXT
```

- [ ] **Step 3: Commit and push the rule anchor**

```bash
git add AGENTS.md .agents/rules/tsuzuki-development.md
git commit -m "docs: anchor Tsuzuki agent execution rules"
git push
```

- [ ] **Step 4: Wait for Fast CI**

Because this task changes only Markdown, path filters may skip Fast CI. If it is skipped, that is acceptable for Task 0.

Acceptance:

```text
AGENTS.md exists
.agents/rules/tsuzuki-development.md exists
no Kotlin/Mihon implementation file changed
```

---

### Task 1: Accept the existing canonical identity model

**Files:**
- Existing: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalIdentityState.kt`
- Existing: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalTitle.kt`
- Existing: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/ExternalIdentity.kt`
- Existing test: `domain/src/test/java/tachiyomi/domain/tsuzuki/model/CanonicalTitleTest.kt`

**Interfaces:**
- Produces:
  - `CanonicalIdentityState`
  - `CanonicalTitle`
  - `ExternalIdentity`

- [ ] **Step 1: Inspect, do not rewrite by default**

Confirm the existing files still match these contracts:

```kotlin
enum class CanonicalIdentityState {
    RESOLVED,
    PARTIALLY_RESOLVED,
    SOURCE_ONLY,
}
```

```kotlin
data class CanonicalTitle(
    val id: String,
    val displayTitle: String,
    val identityState: CanonicalIdentityState,
    val createdAt: Long,
    val updatedAt: Long,
)
```

```kotlin
data class ExternalIdentity(
    val canonicalTitleId: String,
    val provider: String,
    val externalId: String,
    val verified: Boolean,
    val createdAt: Long,
)
```

- [ ] **Step 2: Confirm the existing tests cover both invariants**

The test file must prove:

```text
SOURCE_ONLY title does not require provider identity
external provider ID does not replace CanonicalTitle.id
```

- [ ] **Step 3: Push only if a correction was required**

If no code change is necessary, do not create an empty commit.

- [ ] **Step 4: Use Fast CI as the acceptance gate**

Required green jobs:

```text
Format
Kotlin Compile
Unit Tests
SQLDelight Migrations
```

If the latest relevant Fast CI run is already green for the current code, Task 1 may be accepted from that evidence.

---

### Task 2: Add Library and logical reading-source mapping models

**Files:**
- Modify: `domain/src/test/java/tachiyomi/domain/tsuzuki/model/CanonicalTitleTest.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/LibraryStatus.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalLibraryEntry.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/SourceMappingAvailability.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/model/SourceTitleMapping.kt`

**Interfaces:**
- Consumes: `CanonicalTitle.id: String`.
- Produces:
  - `LibraryStatus`
  - `CanonicalLibraryEntry`
  - `SourceMappingAvailability`
  - `SourceTitleMapping`

- [ ] **Step 1: Add the red tests**

Append inside `CanonicalTitleTest`:

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

- [ ] **Step 2: Commit and push the red phase**

```bash
git add domain/src/test/java/tachiyomi/domain/tsuzuki/model/CanonicalTitleTest.kt
git commit -m "test(tsuzuki): define library and source mapping behavior"
git push
```

- [ ] **Step 3: Confirm expected Unit Tests failure in Fast CI**

Expected failure class:

```text
unresolved CanonicalLibraryEntry / LibraryStatus / SourceTitleMapping / SourceMappingAvailability
```

If CI fails for an unrelated reason, stop and classify the unrelated failure before implementing.

- [ ] **Step 4: Add `LibraryStatus`**

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

- [ ] **Step 5: Add `CanonicalLibraryEntry`**

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

Do not add source IDs, tracker IDs, or provider IDs to this type.

- [ ] **Step 6: Add `SourceMappingAvailability`**

```kotlin
package tachiyomi.domain.tsuzuki.model

enum class SourceMappingAvailability {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
}
```

- [ ] **Step 7: Add `SourceTitleMapping`**

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

`mihonMangaId` remains nullable because a synchronized logical mapping may be known on a device before the local Mihon row exists.

- [ ] **Step 8: Commit and push the green implementation**

```bash
git add \
  domain/src/main/java/tachiyomi/domain/tsuzuki/model/LibraryStatus.kt \
  domain/src/main/java/tachiyomi/domain/tsuzuki/model/CanonicalLibraryEntry.kt \
  domain/src/main/java/tachiyomi/domain/tsuzuki/model/SourceMappingAvailability.kt \
  domain/src/main/java/tachiyomi/domain/tsuzuki/model/SourceTitleMapping.kt

git commit -m "feat(tsuzuki): add library and source mapping models"
git push
```

- [ ] **Step 9: Wait for Fast CI acceptance**

All four Fast CI jobs must be green before Task 3.

---

### Task 3: Add repository contracts and canonical-title materialization

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/repository/CanonicalTitleRepository.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/repository/CanonicalLibraryRepository.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/repository/SourceTitleMappingRepository.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitle.kt`
- Create: `domain/src/test/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitleTest.kt`

**Interfaces:**
- Consumes: Task 1-2 models.
- Produces:
  - `CanonicalTitleRepository`
  - `CanonicalLibraryRepository`
  - `SourceTitleMappingRepository`
  - `MaterializeCanonicalTitle.fromCatalog(...)`
  - `MaterializeCanonicalTitle.fromSource(...)`

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

- [ ] **Step 2: Add the red materialization test**

Create `MaterializeCanonicalTitleTest.kt`:

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

- [ ] **Step 3: Commit and push the red test/contracts**

```bash
git add domain/src/main/java/tachiyomi/domain/tsuzuki/repository \
  domain/src/test/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitleTest.kt

git commit -m "test(tsuzuki): define canonical materialization contract"
git push
```

- [ ] **Step 4: Confirm the expected red CI failure**

Expected Unit Tests/Kotlin failure:

```text
MaterializeCanonicalTitle unresolved
```

- [ ] **Step 5: Implement the interactor with a production injectable constructor and deterministic test constructor**

Create `MaterializeCanonicalTitle.kt`:

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

- [ ] **Step 6: Commit and push the green implementation**

```bash
git add domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitle.kt
git commit -m "feat(tsuzuki): add canonical title materialization"
git push
```

- [ ] **Step 7: Wait for Fast CI acceptance**

All four jobs must be green.

---

### Task 4: Add SQLDelight canonical persistence schema

**Files:**
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_titles.sq`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_external_identities.sq`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_library_entries.sq`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_source_mappings.sq`
- Create: `data/src/main/sqldelight/tachiyomi/migrations/15.sqm`

**Interfaces:**
- Consumes: domain field definitions from Tasks 1-3.
- Produces: generated SQLDelight APIs used by Task 5.

- [ ] **Step 1: Add `tsuzuki_titles.sq`**

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

- [ ] **Step 2: Add `tsuzuki_external_identities.sq`**

```sql
import kotlin.Boolean;

CREATE TABLE tsuzuki_external_identities(
    canonical_title_id TEXT NOT NULL,
    provider TEXT NOT NULL,
    external_id TEXT NOT NULL,
    verified INTEGER AS Boolean NOT NULL,
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

- [ ] **Step 3: Add `tsuzuki_library_entries.sq`**

```sql
import kotlin.Boolean;

CREATE TABLE tsuzuki_library_entries(
    canonical_title_id TEXT NOT NULL PRIMARY KEY,
    status TEXT NOT NULL,
    favorite INTEGER AS Boolean NOT NULL,
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

- [ ] **Step 4: Add `tsuzuki_source_mappings.sq`**

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
    verified_by_user INTEGER AS Boolean NOT NULL,
    availability TEXT NOT NULL,
    preferred_override INTEGER AS Boolean NOT NULL,
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

- [ ] **Step 5: Add migration `15.sqm`**

Create the same four tables and indexes from the `.sq` files, but omit all named query blocks.

The migration must preserve exactly:

```text
column names
column types
primary keys
foreign keys
unique constraints
indexes
```

- [ ] **Step 6: Commit and push the schema**

```bash
git add \
  data/src/main/sqldelight/tachiyomi/data/tsuzuki_titles.sq \
  data/src/main/sqldelight/tachiyomi/data/tsuzuki_external_identities.sq \
  data/src/main/sqldelight/tachiyomi/data/tsuzuki_library_entries.sq \
  data/src/main/sqldelight/tachiyomi/data/tsuzuki_source_mappings.sq \
  data/src/main/sqldelight/tachiyomi/migrations/15.sqm

git commit -m "feat(tsuzuki): add canonical persistence schema"
git push
```

- [ ] **Step 7: Wait for Fast CI**

Required acceptance evidence:

```text
Format: green
Kotlin Compile: green
Unit Tests: green
SQLDelight Migrations: green
```

A SQLDelight failure must be corrected from the actual CI error; do not redesign the schema speculatively.

---

### Task 5: Implement Metro-bound data repositories

**Files:**
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/CanonicalTitleRepositoryImpl.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/CanonicalLibraryRepositoryImpl.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/SourceTitleMappingRepositoryImpl.kt`

**Interfaces:**
- Consumes: repository contracts from Task 3 and generated SQLDelight APIs from Task 4.
- Produces: Metro AppScope implementations.

- [ ] **Step 1: Implement `CanonicalTitleRepositoryImpl`**

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

- [ ] **Step 2: Implement `CanonicalLibraryRepositoryImpl`**

```kotlin
package tachiyomi.data.tsuzuki

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

- [ ] **Step 3: Implement `SourceTitleMappingRepositoryImpl`**

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

- [ ] **Step 4: Commit and push repository implementations**

```bash
git add data/src/main/java/tachiyomi/data/tsuzuki
git commit -m "feat(tsuzuki): persist canonical identity state"
git push
```

- [ ] **Step 5: Use Fast CI as code-generation/DI verification**

Required green jobs:

```text
Kotlin Compile
Unit Tests
SQLDelight Migrations
Format
```

If SQLDelight-generated mapper signatures differ from the plan, adjust only argument order/types to the generated API. Do not change domain ownership or schema semantics to make compilation easier.

---

### Task 6: Final canonical-foundation acceptance and full-verify checkpoint

**Files:**
- Modify only Task 0-5 files if evidence requires a correction.
- Do not start Catalog/Kitsu/Search work.

**Interfaces:**
- Consumes: all Task 0-5 outputs.
- Produces: accepted canonical foundation for the next implementation plan.

- [ ] **Step 1: Inspect the final branch diff**

```bash
git diff tsuzuki/bootstrap...HEAD -- \
  AGENTS.md \
  .agents/rules \
  domain/src/main/java/tachiyomi/domain/tsuzuki \
  domain/src/test/java/tachiyomi/domain/tsuzuki \
  data/src/main/java/tachiyomi/data/tsuzuki \
  data/src/main/sqldelight/tachiyomi/data/tsuzuki_* \
  data/src/main/sqldelight/tachiyomi/migrations/15.sqm
```

Verify:

```text
no provider ID is CanonicalTitle.id
no Mihon Manga field was repurposed as canonical identity
Library membership has no source dependency
SourceTitleMapping.mihonMangaId remains nullable
no Reader code changed
no downloader code changed
no extension execution code changed
no Kitsu network code added
no Search UI added
no Canonical Chapter Engine added
no Drive/tracker behavior added
```

- [ ] **Step 2: Confirm the latest Fast CI is entirely green**

Do not create a new code commit merely to trigger another run if the current head already has green evidence.

- [ ] **Step 3: Request one full release verification checkpoint**

If no code correction is needed, create an empty checkpoint commit whose message requests full verification:

```bash
git commit --allow-empty -m "chore: canonical foundation acceptance [full-ci]"
git push
```

An empty checkpoint commit is allowed here specifically because `[full-ci]` is the CI control surface.

- [ ] **Step 4: Wait for `Full Verify`**

Required:

```text
assembleRelease succeeds
no APK artifact is required
```

This foundation is domain/data infrastructure with no user-visible behavior, so an APK checkpoint is optional rather than an acceptance requirement.

- [ ] **Step 5: Do not merge automatically**

Keep PR #2 in draft until human review of:

```text
final diff
Fast CI evidence
Full Build evidence
architecture invariants
```

---

## Acceptance criteria for the whole plan

The canonical foundation is accepted only when all are true:

1. `CanonicalTitle.id` is Tsuzuki-owned and not a provider ID.
2. `SOURCE_ONLY` titles are valid.
3. Provider identities are stored separately.
4. Canonical Library membership does not require a reading source.
5. Logical source mappings may exist without a device-local Mihon manga row.
6. Tsuzuki persistence uses dedicated SQLDelight tables.
7. Existing Mihon `mangas` and `chapters` remain operational models, not canonical identities.
8. Metro resolves the new repository implementations.
9. Fast CI is green at final head.
10. Full Verify is green at the checkpoint; APK generation is not required for this non-UI foundation.
11. No future MVP-V1 subsystem leaked into this plan.

---

## AGY launch prompt

Use the following as the initial AGY prompt. It is intentionally short; durable context is in repository files.

```text
<OBJECTIVE_AND_PERSONA>
You are the AGY orchestrator for Tsuzuki. Coordinate implementation; do not broaden architecture. Use Codex as an implementation worker when useful.
</OBJECTIVE_AND_PERSONA>

<CONTEXT>
Repository: jssantogit/mihon
Branch: tsuzuki/mvp-v1-canonical-foundation
Draft PR: #2

Read, in this order:
1. AGENTS.md if present
2. .agents/rules/tsuzuki-development.md if present
3. docs/TSUZUKI-SPEC.md
4. docs/TSUZUKI-DEVELOPMENT.md
5. docs/superpowers/plans/2026-09-17-tsuzuki-mvp-v1-canonical-foundation-ci-first.md

Current development model is CI-first. Heavy Gradle/Kotlin verification belongs to GitHub Actions. Local focused checks are optional.
</CONTEXT>

<CONSTRAINTS>
- Stay inside the active plan task.
- Do not implement later Tsuzuki phases early.
- Preserve Mihon Reader/downloader/extensions/source execution.
- Use evidence, not confidence, for acceptance.
- Maximum two delta correction cycles per task.
- Stop as BLOCKED rather than guessing after repeated failure.
</CONSTRAINTS>

<INSTRUCTIONS>
Based on the repository context above:
1. Inspect the current branch and determine the first incomplete task in the active plan.
2. Write a compact scope contract for only that task: allowed paths, forbidden paths, acceptance, CI gate, stop conditions.
3. Execute EXPLORE -> PLAN -> EXECUTE -> VERIFY.
4. Commit and push small reviewable changes.
5. Wait for Fast CI instead of running heavy local Gradle work.
6. If CI fails, perform a delta retry using the actual failure evidence.
7. Stop after the task reaches acceptance. Do not automatically start the next task in the same invocation.
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
One task per invocation. CI is authoritative. Preserve scope. Stop after acceptance.
</RECAP>
```

---

## Per-task continuation prompt

After reviewing one accepted task, continue with:

```text
<CONTEXT>
Use the same Tsuzuki repository, branch, rules, spec, development workflow, and CI-first implementation plan already loaded.
The previous task is accepted.
</CONTEXT>

<INSTRUCTIONS>
Based on the preceding repository context, execute only the next incomplete task in `docs/superpowers/plans/2026-09-17-tsuzuki-mvp-v1-canonical-foundation-ci-first.md`.
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

Do not start these until this plan is accepted:

```text
1. CatalogProvider + Kitsu + unified Search/Discover
2. Canonical Library migration + source preferences + Source Resolver
3. Canonical Chapter Engine + ChapterVariant + coverage/gap detection
4. Reader/progress adapter + per-chapter fallback + tracker-safe canonical progress
```
