# Tsuzuki Modular Runtime — Dev B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn Mihon extensions into Tsuzuki Add-ons and make canonical chapter reading resolve content at read time, with per-title provider preference, explicit fallback, provider-neutral Reader preparation, canonical downloads, and local content.

**Architecture:** Dev B treats Mihon as the first Add-on runtime rather than the product model. A ContentBinding connects a CanonicalTitle to an Add-on’s operational title identity; ContentProvider returns ContentOptions for one CanonicalChapter; ContentResolver ranks/selects options without changing canonical progress. Reader/download compatibility remains behind adapters while new provider-neutral content preparation is introduced.

**Tech Stack:** Kotlin, Metro DI, SQLDelight, Mihon ExtensionManager/SourceManager, existing canonical Reader/downloader, Jetpack Compose.

**Spec:** `docs/superpowers/specs/2026-09-20-tsuzuki-modular-runtime-architecture-design.md`

## Global Constraints

- Branch only after the Wave 0 foundation is merged.
- Do not modify Integration/Kitsu/MAL/search authority code.
- Do not modify Home/Settings main shell or Supabase sync.
- A Mihon extension is one visible Tsuzuki Add-on even if it exposes several internal Source objects.
- Source search may be used internally to establish a ContentBinding but must not become user-facing catalog browsing.
- Preferred Add-on is per CanonicalTitle.
- Automatic fallback is global opt-in and off by default.
- Language preferences order options; they never hide other languages.
- Removing an Add-on must not erase canonical chapter/progress/download state.
- Reader progress remains keyed by CanonicalChapter.
- Production torrent networking is a separate delivery subproject after the provider-neutral content seam is green; this plan defines the stable torrent delivery contract and artifact preparation boundary so the later engine does not reshape Core.
- Fast CI per task; `[ci-full]` on the Dev B final checkpoint.

## Review Focus

- Multi-source Mihon extension must render as one Add-on while still resolving its internal language/source variants.
- A stale ContentBinding must be repaired without creating a duplicate CanonicalTitle.
- Preferred provider unavailable with fallback off must open selector, not silently choose another option.
- Switching provider for the same chapter must preserve canonical page/read progress.
- Add-on uninstall must not invalidate a completed canonical download.

---

### Task B1: Implement Add-on registry and Mihon extension facade

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/addon/model/InstalledAddon.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/addon/repository/AddonRepository.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/addon/MihonAddonRepository.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/addon/DefaultAddonRegistry.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/addon/MihonAddonRepositoryTest.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/addon/DefaultAddonRegistryTest.kt`

**Interfaces:**
- Consumes: Wave 0 `AddonId`, `AddonRegistry`, `ContentProvider`, `ChapterProbeProvider`; existing `ExtensionManager` and installed extension models.
- Produces: `Flow<List<InstalledAddon>>` and enabled provider registry.

- [ ] **Step 1: Write the failing one-extension/one-Add-on test**

~~~kotlin
@Test
fun `multi source extension is exposed as one addon`() {
    val extension = installedExtension(
        pkgName = "eu.kanade.tachiyomi.extension.en.example",
        name = "Example",
        sources = listOf(
            fakeSource(id = 1L, lang = "en"),
            fakeSource(id = 2L, lang = "pt-BR"),
        ),
    )

    val addons = repositoryFor(extension).snapshot()

    assertEquals(1, addons.size)
    assertEquals(AddonId(extension.pkgName), addons.single().id)
    assertEquals(setOf(1L, 2L), addons.single().mihonSourceIds.toSet())
}
~~~

- [ ] **Step 2: Run test and confirm failure**

~~~bash
./gradlew :app:testDebugUnitTest --tests '*MihonAddonRepositoryTest'
~~~

- [ ] **Step 3: Implement Add-on facade**

~~~kotlin
data class InstalledAddon(
    val id: AddonId,
    val displayName: String,
    val enabled: Boolean,
    val versionName: String,
    val mihonSourceIds: List<Long>,
    val hasSettings: Boolean,
)

interface AddonRepository {
    fun observeInstalled(): Flow<List<InstalledAddon>>
    suspend fun snapshot(): List<InstalledAddon>
    suspend fun setEnabled(id: AddonId, enabled: Boolean)
}
~~~

Use extension package name as the initial stable AddonId for Mihon-backed Add-ons. Internal Source IDs remain runtime details.

- [ ] **Step 4: Implement registry filtering**

`DefaultAddonRegistry.contentProviders()` and `chapterProbeProviders()` must return providers only for enabled/installed Add-ons.

A synced “desired Add-on” record is not enough to register executable code.

- [ ] **Step 5: Add executable-restore safety test**

~~~kotlin
@Test
fun `registry never exposes addon that is only present in cloud desired state`() {
    val registry = registry(installed = emptyList(), desiredFromCloud = setOf(AddonId("pkg")))
    assertTrue(registry.contentProviders().isEmpty())
}
~~~

- [ ] **Step 6: Run tests and commit**

~~~bash
./gradlew :app:testDebugUnitTest --tests '*MihonAddonRepositoryTest' \
  :app:testDebugUnitTest --tests '*DefaultAddonRegistryTest' \
  spotlessCheck
git add domain/src/main/java/tachiyomi/domain/tsuzuki/addon \
  app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/addon \
  app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/addon
git commit -m "feat(tsuzuki): expose Mihon extensions as addons"
~~~

---

### Task B2: Persist provider-neutral ContentBindings

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/content/repository/ContentBindingRepository.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/content/ContentBindingRepositoryImpl.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/content/interactor/ResolveContentBinding.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/MihonReadingSourceGateway.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/content/ResolveContentBindingTest.kt`
- Test: `data/src/test/java/tachiyomi/data/tsuzuki/content/ContentBindingRepositoryImplTest.kt`

**Interfaces:**
- Consumes: existing title matching/search machinery and Wave 0 `ContentBinding`.
- Produces: `ResolveContentBinding.execute(canonicalTitleId, addonId)`.

- [ ] **Step 1: Write a failing binding reuse test**

~~~kotlin
@Test
fun `existing addon binding is reused without title search`() = runTest {
    repository.upsert(binding("title", AddonId("mangadex"), "remote-123"))
    val result = resolver.execute("title", AddonId("mangadex")).getOrThrow()

    assertEquals("remote-123", result.providerTitleKey)
    assertEquals(0, searchGateway.searchCalls)
}
~~~

- [ ] **Step 2: Write stale-binding repair test**

~~~kotlin
@Test
fun `stale binding is repaired inside same canonical title`() = runTest {
    repository.upsert(unavailableBinding("title", AddonId("mangadex"), "old"))
    searchGateway.results = listOf(highConfidenceCandidate(providerTitleKey = "new"))

    val repaired = resolver.execute("title", AddonId("mangadex")).getOrThrow()

    assertEquals("title", repaired.canonicalTitleId)
    assertEquals("new", repaired.providerTitleKey)
}
~~~

- [ ] **Step 3: Implement repository**

~~~kotlin
interface ContentBindingRepository {
    suspend fun get(canonicalTitleId: String, addonId: AddonId): ContentBinding?
    suspend fun getByTitle(canonicalTitleId: String): List<ContentBinding>
    suspend fun upsert(binding: ContentBinding)
    suspend fun markUnavailable(bindingId: String, updatedAt: Long)
}
~~~

- [ ] **Step 4: Implement binding resolution using internal Mihon search**

For Mihon Add-ons:
1. inspect extension’s internal Sources;
2. reuse old source-title matching score logic;
3. require high confidence or user confirmation semantics already established by canonical mapping work;
4. persist runtime payload containing Mihon source/manga operational identity;
5. never create a new CanonicalTitle from a source search result.

- [ ] **Step 5: Run tests and commit**

~~~bash
./gradlew :domain:testDebugUnitTest --tests '*ResolveContentBindingTest' \
  :data:testDebugUnitTest --tests '*ContentBindingRepositoryImplTest' \
  spotlessCheck
git add domain/src/main/java/tachiyomi/domain/tsuzuki/content \
  data/src/main/java/tachiyomi/data/tsuzuki/content \
  app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/MihonReadingSourceGateway.kt \
  domain/src/test/java/tachiyomi/domain/tsuzuki/content \
  data/src/test/java/tachiyomi/data/tsuzuki/content
git commit -m "feat(tsuzuki): add provider neutral content bindings"
~~~

---

### Task B3: Implement Mihon ContentProvider and ChapterProbeProvider

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/addon/MihonContentProvider.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/addon/MihonChapterProbeProvider.kt`
- Reuse/Modify: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/MihonChapterInventoryGateway.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/addon/MihonContentProviderTest.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/addon/MihonChapterProbeProviderTest.kt`

**Interfaces:**
- Produces: `ContentOption` and ADDON_PROVISIONAL `ChapterEvidence`.
- Consumes: `ContentBindingRepository`, canonical chapter parser, Mihon source chapter inventory.

- [ ] **Step 1: Write failing ContentOption test**

~~~kotlin
@Test
fun `matching source release becomes content option for canonical chapter`() = runTest {
    val options = provider.resolve("title", "canonical-chapter-37").getOrThrow()

    assertEquals(AddonId("mangadex"), options.single().addonId)
    assertEquals("pt-BR", options.single().language)
    assertIs<ContentDelivery.Mihon>(options.single().delivery)
}
~~~

- [ ] **Step 2: Write provisional evidence test**

~~~kotlin
@Test
fun `source chapter ahead of integration becomes addon provisional evidence`() = runTest {
    val evidence = probe.probe("title").getOrThrow()
    val newChapter = evidence.single { it.rawLabel == "Chapter 211" }

    assertEquals(ChapterEvidenceAuthority.ADDON_PROVISIONAL, newChapter.authority)
    assertEquals(ProducerKind.ADDON, newChapter.producerKind)
}
~~~

- [ ] **Step 3: Implement provider/probe**

ContentProvider returns options only for releases that reconcile to the requested CanonicalChapter.

ChapterProbeProvider may report all observed source chapter labels as provisional evidence; Dev A’s reconciler owns whether those observations create/promote canonical chapters.

For background probes, if the Add-on has no persisted ContentBinding for the canonical title, return an empty evidence list. Do not perform broad title search from a periodic/background probe.

- [ ] **Step 4: Verify no source inventory mutates canonical chapters directly**

Add a test using a fake CanonicalChapterRepository and assert the Mihon provider/probe never calls `upsert` on it.

- [ ] **Step 5: Run tests and commit**

~~~bash
./gradlew :app:testDebugUnitTest --tests '*MihonContentProviderTest' \
  :app:testDebugUnitTest --tests '*MihonChapterProbeProviderTest' \
  spotlessCheck
git add app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/addon \
  app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/MihonChapterInventoryGateway.kt \
  app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/addon
git commit -m "feat(tsuzuki): resolve Mihon addon chapter content"
~~~

---

### Task B4: Implement per-title preference, ranking, and fallback policy

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/content/repository/ContentPreferenceRepository.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/content/ContentPreferenceRepositoryImpl.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/content/interactor/ResolveChapterContent.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/content/interactor/RankContentOptions.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/content/model/ContentResolution.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/reader/model/CanonicalReaderPreference.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/reader/interactor/SetCanonicalAutomaticFallback.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/content/ResolveChapterContentTest.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/content/RankContentOptionsTest.kt`

**Interfaces:**
- Produces: `ContentResolution.Direct`, `NeedsSelection`, `Unavailable`.
- Consumes: `AddonRegistry.contentProviders()`, content preference repo, global reading preferences.

- [ ] **Step 1: Write policy tests**

~~~kotlin
@Test fun `first read requires selector even when one provider exists`() = runTest {
    assertIs<ContentResolution.NeedsSelection>(resolve(titlePreference = null, options = oneOption()))
}

@Test fun `preferred addon opens directly when chapter is available`() = runTest {
    assertIs<ContentResolution.Direct>(
        resolve(titlePreference = AddonId("mangadex"), options = optionsFrom("mangadex", "mangafire")),
    )
}

@Test fun `missing preferred addon opens selector when automatic fallback is off`() = runTest {
    assertIs<ContentResolution.NeedsSelection>(
        resolve(titlePreference = AddonId("mangadex"), options = optionsFrom("mangafire"), autoFallback = false),
    )
}

@Test fun `missing preferred addon selects ranked fallback only when enabled`() = runTest {
    val result = resolve(
        titlePreference = AddonId("mangadex"),
        options = optionsFrom("mangafire"),
        autoFallback = true,
    )
    assertIs<ContentResolution.Direct>(result)
}
~~~

- [ ] **Step 2: Write language visibility test**

~~~kotlin
@Test
fun `preferred language reorders but never filters alternatives`() {
    val ranked = rank(options = listOf(enOption(), ptOption()), preferredLanguages = listOf("pt-BR"))
    assertEquals("pt-BR", ranked.first().language)
    assertEquals(2, ranked.size)
}
~~~

- [ ] **Step 3: Implement repository and policy**

~~~kotlin
sealed interface ContentResolution {
    data class Direct(
        val option: ContentOption,
        val usedFallback: Boolean,
    ) : ContentResolution

    data class NeedsSelection(
        val options: List<ContentOption>,
        val preferredAddonId: AddonId?,
        val preferredUnavailable: Boolean,
    ) : ContentResolution

    data object Unavailable : ContentResolution
}
~~~

Move automatic-fallback storage to a global reading preference. Do not keep it title-scoped after migration.

- [ ] **Step 4: Add progress-preservation test**

Resolve the same CanonicalChapter with two different options and assert canonical progress repository retains the same chapter/page state; provider choice must not key progress.

- [ ] **Step 5: Run tests and commit**

~~~bash
./gradlew :domain:testDebugUnitTest --tests '*ResolveChapterContentTest' \
  :domain:testDebugUnitTest --tests '*RankContentOptionsTest' \
  spotlessCheck
git add domain/src/main/java/tachiyomi/domain/tsuzuki/content \
  data/src/main/java/tachiyomi/data/tsuzuki/content \
  domain/src/main/java/tachiyomi/domain/tsuzuki/reader \
  domain/src/test/java/tachiyomi/domain/tsuzuki/content
git commit -m "feat(tsuzuki): add per-title content resolution policy"
~~~

---

### Task B5: Replace source-level Reader preparation with ContentOption selection

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/reader/model/PreparedChapterContent.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/reader/service/ChapterContentPreparer.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/MihonChapterContentPreparer.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/reader/interactor/PrepareCanonicalChapterForReader.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/reader/interactor/RecordCanonicalReaderProgress.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/MihonCanonicalReaderGateway.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/reader/PrepareCanonicalChapterForReaderTest.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/MihonChapterContentPreparerTest.kt`

**Interfaces:**
- Produces: provider-neutral preparation seam.
- Consumes: `ResolveChapterContent`, existing canonical progress, Mihon compatibility gateway.

- [ ] **Step 1: Define preparation variants in a failing test**

~~~kotlin
sealed interface PreparedChapterContent {
    data class MihonOperational(
        val mangaId: Long,
        val chapterId: Long,
        val sourceId: Long,
    ) : PreparedChapterContent

    data class LocalArchive(val uri: String) : PreparedChapterContent
    data class LocalDirectory(val uri: String) : PreparedChapterContent
    data class CanonicalDownload(val uri: String, val format: String) : PreparedChapterContent
}
~~~

Test that a `ContentDelivery.Mihon` becomes `PreparedChapterContent.MihonOperational` without changing CanonicalChapter ID.

- [ ] **Step 2: Run targeted tests and confirm failure**

~~~bash
./gradlew :domain:testDebugUnitTest --tests '*PrepareCanonicalChapterForReaderTest' \
  :app:testDebugUnitTest --tests '*MihonChapterContentPreparerTest'
~~~

- [ ] **Step 3: Implement preparer and adapt interactor**

`PrepareCanonicalChapterForReader` must:
1. resolve content;
2. return a selector-required state if needed;
3. prepare the selected option;
4. pass current canonical progress to the Reader compatibility layer;
5. never refresh source inventories as a precondition for canonical chapter existence.

- [ ] **Step 4: Keep Mihon compatibility as one preparer**

Do not create fake Mihon source/manga objects for LocalArchive/LocalDirectory/CanonicalDownload variants.

When canonical progress marks a newly observed chapter as read, also acknowledge that chapter in the chapter-update-state repository so a Home badge can move from +3 → +2 → +1 → none without treating the entire unread backlog as “new”.

- [ ] **Step 5: Run tests and commit**

~~~bash
./gradlew :domain:testDebugUnitTest --tests '*PrepareCanonicalChapterForReaderTest' \
  :app:testDebugUnitTest --tests '*MihonChapterContentPreparerTest' \
  spotlessCheck
git add domain/src/main/java/tachiyomi/domain/tsuzuki/reader \
  app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/MihonChapterContentPreparer.kt \
  app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/MihonCanonicalReaderGateway.kt \
  domain/src/test/java/tachiyomi/domain/tsuzuki/reader \
  app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki
git commit -m "refactor(tsuzuki): prepare reader from content options"
~~~

---

### Task B6: Build content selector, Reader “Change source”, and Add-ons Settings

**Files:**
- Create: `app/src/main/java/eu/kanade/presentation/tsuzuki/content/ContentOptionSelectorSheet.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/content/ContentSelectorScreenModel.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/reader/appbars/ReaderAppBars.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsReaderScreen.kt`
- Create: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTsuzukiAddonsScreen.kt`
- Reuse: `app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/ExtensionStoresScreen.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/content/ContentSelectorScreenModelTest.kt`

**Interfaces:**
- Produces: standalone Add-ons settings destination and selector UI Dev C can wire.
- Consumes: `ResolveChapterContent`, `ContentPreferenceRepository`, `AddonRepository`.

- [ ] **Step 1: Write selector presentation tests**

Assert each option exposes:
- Add-on display name;
- language;
- scanlation group if present;
- release date if present.

Assert alternatives are never hidden because one option ranked first.

- [ ] **Step 2: Write preference-change confirmation test**

When the preferred Add-on is unavailable and user manually chooses a different Add-on, selection returns:

~~~kotlin
data class SelectionResult(
    val option: ContentOption,
    val offerSetAsPreferred: Boolean,
)
~~~

`offerSetAsPreferred` is true; preference does not change until confirmation.

- [ ] **Step 3: Implement selector and Reader action**

Reader toolbar action “Trocar fonte” resolves the current CanonicalChapter again and opens selector.

Choosing another option rematerializes operational content for the same canonical chapter and resumes canonical page/progress state where valid.

- [ ] **Step 4: Implement empty/error selector behavior**

If no ContentOption exists, show “Nenhuma opção de leitura encontrada” with:
- Retry;
- open Add-ons Settings.

A network/Add-on failure for one provider must not suppress successful options from other providers.

- [ ] **Step 5: Add global reading preferences**

Extend the existing Reader Settings screen with:
- preferred languages, ordered list, affecting ranking only;
- Automatic fallback switch, default OFF.

Do not add per-title language controls.

- [ ] **Step 6: Implement Add-ons Settings facade**

Show:
- repositories entry;
- installed Add-ons;
- update state;
- enable/disable/uninstall;
- Add-on settings where extension supports them.

Do not expose Sources as top-level cards.

- [ ] **Step 7: Run tests and compile**

~~~bash
./gradlew :app:testDebugUnitTest --tests '*ContentSelectorScreenModelTest' \
  :app:compileDebugKotlin \
  spotlessCheck
~~~

- [ ] **Step 8: Commit**

~~~bash
git add app/src/main/java/eu/kanade/presentation/tsuzuki/content \
  app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/content \
  app/src/main/java/eu/kanade/presentation/reader \
  app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt \
  app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsReaderScreen.kt \
  app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTsuzukiAddonsScreen.kt \
  app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/content
git commit -m "feat(tsuzuki): add chapter content selector"
~~~

---

### Task B7: Make downloads canonical and add Local Content adapter

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/download/repository/CanonicalDownloadRepository.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/download/CanonicalDownloadRepositoryImpl.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/MihonCanonicalDownloadGateway.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/addon/LocalContentProvider.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ChapterLoader.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/download/CanonicalDownloadRepositoryTest.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/addon/LocalContentProviderTest.kt`

**Interfaces:**
- Produces: downloaded-artifact-first resolution and local Add-on options.
- Consumes: existing downloader/LocalSource/archive loaders.

- [ ] **Step 1: Write uninstall-survival test**

~~~kotlin
@Test
fun `canonical download remains readable after origin addon disappears`() = runTest {
    repository.upsert(
        CanonicalDownloadArtifact(
            canonicalChapterId = "chapter-1",
            localUri = "content://downloads/chapter-1.cbz",
            format = "CBZ",
            originatingAddonId = AddonId("mangadex"),
            originatingOptionKey = "option-1",
            completedAt = 100L,
            checksum = null,
        ),
    )

    addonRegistry.removeAll()
    assertNotNull(repository.get("chapter-1"))
}
~~~

- [ ] **Step 2: Implement canonical download repository**

Do not require sourceId/mangaId to reopen a completed artifact.

- [ ] **Step 3: Adapt download command**

Download selection policy:
1. downloaded artifact already exists → no duplicate;
2. preferred Add-on has requested chapter → use it;
3. otherwise require selector.

After success, persist CanonicalDownloadArtifact.

- [ ] **Step 4: Implement LocalContentProvider**

Map local archive/directory/EPUB availability into ContentOptions using the same canonical chapter resolver contract.

Local option ranks above remote when the exact chapter artifact already exists.

- [ ] **Step 5: Teach Reader preparation to open canonical local artifacts**

Reuse existing `ArchivePageLoader`, `DirectoryPageLoader`, and EPUB behavior. Avoid fake Source objects for new canonical artifact path.

- [ ] **Step 6: Run tests and commit**

~~~bash
./gradlew :domain:testDebugUnitTest --tests '*CanonicalDownloadRepositoryTest' \
  :app:testDebugUnitTest --tests '*LocalContentProviderTest' \
  :app:compileDebugKotlin \
  spotlessCheck
git add domain/src/main/java/tachiyomi/domain/tsuzuki/download \
  data/src/main/java/tachiyomi/data/tsuzuki/download \
  app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki \
  app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ChapterLoader.kt \
  domain/src/test/java/tachiyomi/domain/tsuzuki/download \
  app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/addon
git commit -m "feat(tsuzuki): own downloads by canonical chapter"
~~~

---

### Task B8: Freeze remote-manifest and torrent delivery boundary

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/addon/remote/RemoteAddonManifest.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/addon/remote/RemoteAddonProtocol.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/content/TorrentArtifactEngine.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/content/interactor/PrepareTorrentArtifact.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/addon/remote/RemoteAddonManifestTest.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/content/PrepareTorrentArtifactTest.kt`

**Interfaces:**
- Produces: stable protocol/delivery seam for a separate production torrent-engine plan.
- Consumes: existing `ContentDelivery.Torrent`, `PreparedChapterContent.LocalArchive`/directory path.

- [ ] **Step 1: Define a strict remote manifest schema**

~~~kotlin
@Serializable
data class RemoteAddonManifest(
    val id: String,
    val name: String,
    val version: String,
    val protocolVersion: Int,
    val capabilities: Set<RemoteAddonCapability>,
    val endpoints: RemoteAddonEndpoints,
)

enum class RemoteAddonCapability {
    CONTENT,
    CHAPTER_PROBE,
}
~~~

Reject unknown protocol versions and missing HTTPS endpoints at the parser boundary, except explicitly allowed localhost development endpoints.

- [ ] **Step 2: Write torrent file-selection safety tests**

~~~kotlin
@Test
fun `explicit torrent file index is preserved and not guessed`() = runTest {
    val delivery = ContentDelivery.Torrent(
        infoHash = "abc",
        magnetUri = null,
        fileIndex = 4,
        filePath = "Volume 01/chapter05.cbz",
    )
    val request = preparer.toRequest(delivery)
    assertEquals(4, request.fileIndex)
}

@Test
fun `torrent path traversal is rejected before artifact exposure`() {
    assertFailsWith<IllegalArgumentException> {
        validateTorrentFilePath("../outside.cbz")
    }
}
~~~

- [ ] **Step 3: Define engine contract, not a native dependency**

~~~kotlin
interface TorrentArtifactEngine {
    suspend fun acquire(request: TorrentArtifactRequest): Result<PreparedTorrentArtifact>
}

data class PreparedTorrentArtifact(
    val localUri: String,
    val format: String,
)
~~~

`PrepareTorrentArtifact` converts a completed supported archive/directory artifact into provider-neutral Reader preparation.

- [ ] **Step 4: Keep production networking out of this task**

This task is complete only when:
- Core can represent torrent delivery without Mihon Source identity;
- malicious paths/unsupported formats are rejected;
- a fake engine proves the end-to-end preparation contract.

Selecting/embedding a production libtorrent/JNI engine requires a separate external-dependency/security plan and does not block the modular runtime merge.

- [ ] **Step 5: Run tests and commit**

~~~bash
./gradlew :domain:testDebugUnitTest --tests '*RemoteAddonManifestTest' \
  :domain:testDebugUnitTest --tests '*PrepareTorrentArtifactTest' \
  spotlessCheck
git add domain/src/main/java/tachiyomi/domain/tsuzuki/addon/remote \
  domain/src/main/java/tachiyomi/domain/tsuzuki/content \
  domain/src/test/java/tachiyomi/domain/tsuzuki/addon/remote \
  domain/src/test/java/tachiyomi/domain/tsuzuki/content
git commit -m "feat(tsuzuki): define remote addon and torrent delivery contracts"
~~~

---

### Task B9: Dev B verification and handoff

**Files:**
- Modify only Dev B-owned files if verification exposes regressions.

**Interfaces:**
- Produces: stable content branch for A+B integration.
- Consumes: Tasks B1-B8.

- [ ] **Step 1: Run Dev B acceptance suite**

~~~bash
./gradlew \
  :domain:testDebugUnitTest \
  :data:testDebugUnitTest \
  :app:testDebugUnitTest \
  :app:compileDebugKotlin \
  verifySqlDelightMigration \
  spotlessCheck
~~~

- [ ] **Step 2: Inspect forbidden coupling**

Confirm:
- no content resolver calls Kitsu/MAL concrete classes;
- no Add-on package ID becomes CanonicalTitle ID;
- no Reader progress key includes AddonId/sourceId;
- no canonical download reopen requires installed originating Add-on;
- no remote manifest parser executes downloaded code.

- [ ] **Step 3: Commit verification-only corrections if required**

~~~bash
git commit -m "test(tsuzuki): close content runtime regressions [ci-full]"
~~~

- [ ] **Step 4: Push and require CI v2 full green**

- [ ] **Step 5: Emit handoff values from git/CI rather than handwritten guesses**

Run:

~~~bash
printf 'BASE_SHA=%s\n' "$(git merge-base tsuzuki/bootstrap HEAD)"
printf 'HEAD_SHA=%s\n' "$(git rev-parse HEAD)"
git log -1 --oneline
~~~

Then report completed tasks B1-B9, CI run ID/result, and these consumable screens/contracts:

~~~text
AddonRegistry
ContentBindingRepository
ResolveChapterContent
PreparedChapterContent
SettingsTsuzukiAddonsScreen
ContentOptionSelectorSheet
CanonicalDownloadRepository
LocalContentProvider
RemoteAddonManifest
TorrentArtifactEngine
~~~
