package tachiyomi.domain.tsuzuki.download

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.AddonRegistry
import tachiyomi.domain.tsuzuki.addon.ChapterProbeProvider
import tachiyomi.domain.tsuzuki.addon.ContentProvider
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.content.ContentPreference
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCache
import tachiyomi.domain.tsuzuki.content.cache.InFlightContentResolution
import tachiyomi.domain.tsuzuki.content.interactor.RankContentOptions
import tachiyomi.domain.tsuzuki.content.interactor.ResolveChapterContent
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import tachiyomi.domain.tsuzuki.download.interactor.DownloadCanonicalChapter
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadArtifact
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadPreparation
import tachiyomi.domain.tsuzuki.download.repository.CanonicalDownloadRepository
import tachiyomi.domain.tsuzuki.download.service.CanonicalDownloadGateway
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences

class DownloadCanonicalChapterTest {

    @Test
    fun existingArtifactSkipsAddonResolutionAndDownload() = runTest {
        val existing = artifact("existing")
        val fixture = fixture(
            preference = null,
            providers = listOf(provider(option("mangadex"))),
            existingArtifact = existing,
        )

        val result = fixture.download.execute("chapter-1")

        result.shouldBeInstanceOf<CanonicalDownloadPreparation.Complete>()
        result.artifact shouldBe existing
        result.reused shouldBe true
        fixture.gateway.calls shouldBe 0
    }

    @Test
    fun preferredAddonDownloadsAndPersistsCanonicalArtifact() = runTest {
        val preferred = option("mangadex")
        val acquired = artifact("mangadex")
        val fixture = fixture(
            preference = ContentPreference("title-1", AddonId("mangadex"), 1L),
            providers = listOf(provider(preferred), provider(option("mangafire"))),
            gatewayArtifact = acquired,
        )

        val result = fixture.download.execute("chapter-1")

        result.shouldBeInstanceOf<CanonicalDownloadPreparation.Complete>()
        result.artifact shouldBe acquired
        result.reused shouldBe false
        fixture.gateway.lastOption shouldBe preferred
        fixture.repository.value shouldBe acquired
    }

    @Test
    fun missingPreferredAddonRequiresSelectorInsteadOfSilentFallback() = runTest {
        val fallback = option("mangafire")
        val fixture = fixture(
            preference = ContentPreference("title-1", AddonId("mangadex"), 1L),
            providers = listOf(provider(fallback)),
        )

        val result = fixture.download.execute("chapter-1")

        result.shouldBeInstanceOf<CanonicalDownloadPreparation.SelectionRequired>()
        result.preferredAddonId shouldBe AddonId("mangadex")
        result.options shouldBe listOf(fallback)
        fixture.gateway.calls shouldBe 0
    }

    @Test
    fun firstDownloadWithoutPreferenceRequiresSelector() = runTest {
        val first = option("mangadex")
        val second = option("mangafire")
        val fixture = fixture(
            preference = null,
            providers = listOf(provider(first), provider(second)),
        )

        val result = fixture.download.execute("chapter-1")

        result.shouldBeInstanceOf<CanonicalDownloadPreparation.SelectionRequired>()
        result.preferredAddonId shouldBe null
        result.options.map { it.key }.toSet() shouldBe setOf(first.key, second.key)
        fixture.gateway.calls shouldBe 0
    }

    @Test
    fun explicitSelectionDownloadsWithoutChangingCanonicalIdentity() = runTest {
        val selected = option("mangafire")
        val acquired = artifact("mangafire")
        val fixture = fixture(
            preference = null,
            providers = emptyList(),
            gatewayArtifact = acquired,
        )

        val result = fixture.download.execute(
            canonicalChapterId = "chapter-1",
            selectedOption = selected,
        )

        result.shouldBeInstanceOf<CanonicalDownloadPreparation.Complete>()
        result.canonicalChapterId shouldBe "chapter-1"
        fixture.gateway.lastOption shouldBe selected
        fixture.repository.value?.canonicalChapterId shouldBe "chapter-1"
    }

    private fun fixture(
        preference: ContentPreference?,
        providers: List<ContentProvider>,
        existingArtifact: CanonicalDownloadArtifact? = null,
        gatewayArtifact: CanonicalDownloadArtifact? = null,
    ): Fixture {
        val preferenceRepository = FakeContentPreferenceRepository(preference)
        val resolver = ResolveChapterContent(
            addonRegistry = FakeAddonRegistry(providers),
            contentPreferenceRepository = preferenceRepository,
            readerPreferences = CanonicalReaderPreferences(InMemoryPreferenceStore()),
            rankContentOptions = RankContentOptions(),
            contentOptionCache = ContentOptionCache(),
            inFlightContentResolution = InFlightContentResolution(),
        )
        val repository = FakeCanonicalDownloadRepository(existingArtifact)
        val gateway = FakeCanonicalDownloadGateway(gatewayArtifact)
        val download = DownloadCanonicalChapter(
            canonicalChapterRepository = FakeCanonicalChapterRepository(),
            contentPreferenceRepository = preferenceRepository,
            resolveChapterContent = resolver,
            canonicalDownloadRepository = repository,
            canonicalDownloadGateway = gateway,
        )
        return Fixture(download, repository, gateway)
    }

    private data class Fixture(
        val download: DownloadCanonicalChapter,
        val repository: FakeCanonicalDownloadRepository,
        val gateway: FakeCanonicalDownloadGateway,
    )

    private fun provider(vararg options: ContentOption): ContentProvider = object : ContentProvider {
        override val addonId = options.first().addonId

        override suspend fun resolve(
            canonicalTitleId: String,
            canonicalChapterId: String,
        ): Result<List<ContentOption>> = Result.success(options.toList())
    }

    private fun option(addon: String) = ContentOption(
        key = addon + ":chapter-1",
        canonicalChapterId = "chapter-1",
        addonId = AddonId(addon),
        language = "en",
        scanlationGroup = null,
        releaseDate = 100L,
        delivery = ContentDelivery.Mihon(
            sourceId = 7L,
            mangaId = 20L,
            chapterId = 30L,
        ),
    )

    private fun artifact(origin: String) = CanonicalDownloadArtifact(
        canonicalChapterId = "chapter-1",
        localUri = "content://downloads/" + origin + ".cbz",
        format = "CBZ",
        originatingAddonId = AddonId(origin),
        originatingOptionKey = origin + ":chapter-1",
        completedAt = 100L,
        checksum = null,
    )

    private class FakeAddonRegistry(
        private val providers: List<ContentProvider>,
    ) : AddonRegistry {
        override fun contentProviders(): List<ContentProvider> = providers
        override fun chapterProbeProviders(): List<ChapterProbeProvider> = emptyList()
    }

    private class FakeContentPreferenceRepository(
        private var preference: ContentPreference?,
    ) : ContentPreferenceRepository {
        override suspend fun get(canonicalTitleId: String): ContentPreference? =
            preference?.takeIf { it.canonicalTitleId == canonicalTitleId }

        override fun observe(canonicalTitleId: String): Flow<ContentPreference?> =
            MutableStateFlow(preference?.takeIf { it.canonicalTitleId == canonicalTitleId })

        override suspend fun upsert(preference: ContentPreference) {
            this.preference = preference
        }

        override suspend fun delete(canonicalTitleId: String) {
            if (preference?.canonicalTitleId == canonicalTitleId) preference = null
        }
    }

    private class FakeCanonicalChapterRepository : CanonicalChapterRepository {
        private val chapter = CanonicalChapter(
            id = "chapter-1",
            canonicalTitleId = "title-1",
            displayNumber = "1",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 1,
            confidence = 1.0,
            createdAt = 1L,
            updatedAt = 1L,
        )

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String) =
            listOf(chapter).filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(listOf(chapter).filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getById(id: String) = chapter.takeIf { it.id == id }
        override suspend fun getVariantBySourceIdentity(sourceId: Long, sourceChapterId: String): ChapterVariant? = null
        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> = emptyList()
        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> = emptyList()
        override suspend fun upsert(chapter: CanonicalChapter) = Unit
        override suspend fun upsertVariant(variant: ChapterVariant) = Unit
        override suspend fun upsertBatch(chapters: List<CanonicalChapter>, variants: List<ChapterVariant>) = Unit
    }

    private class FakeCanonicalDownloadRepository(
        var value: CanonicalDownloadArtifact?,
    ) : CanonicalDownloadRepository {
        override suspend fun get(canonicalChapterId: String) =
            value?.takeIf { it.canonicalChapterId == canonicalChapterId }

        override suspend fun upsert(artifact: CanonicalDownloadArtifact) {
            value = artifact
        }

        override suspend fun delete(canonicalChapterId: String) {
            if (value?.canonicalChapterId == canonicalChapterId) value = null
        }

        override suspend fun deleteOriginMetadata(addonId: AddonId) = Unit
    }

    private class FakeCanonicalDownloadGateway(
        private val artifact: CanonicalDownloadArtifact?,
    ) : CanonicalDownloadGateway {
        var calls = 0
        var lastOption: ContentOption? = null

        override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false

        override suspend fun acquire(option: ContentOption): Result<CanonicalDownloadArtifact> {
            calls++
            lastOption = option
            return artifact?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("download unavailable"))
        }
    }
}
