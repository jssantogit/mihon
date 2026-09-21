package tachiyomi.domain.tsuzuki.reader

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
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadArtifact
import tachiyomi.domain.tsuzuki.download.repository.CanonicalDownloadRepository
import tachiyomi.domain.tsuzuki.reader.interactor.PrepareCanonicalChapterForReader
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreparation
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.reader.service.ChapterContentPreparer

class PrepareCanonicalChapterForReaderTest {

    @Test
    fun `direct content option prepares reader with canonical progress`() = runTest {
        val option = option("mangadex")
        val fixture = fixture(
            preference = ContentPreference("title-1", AddonId("mangadex"), 1L),
            providers = listOf(provider(option)),
        )
        fixture.reading.progress = CanonicalChapterProgress(
            canonicalChapterId = "chapter-1",
            read = false,
            lastPageRead = 5L,
            lastVariantId = null,
            updatedAt = 100L,
        )

        val result = fixture.prepare.execute("chapter-1")

        result.shouldBeInstanceOf<CanonicalReaderPreparation.Ready>()
        result.canonicalChapterId shouldBe "chapter-1"
        result.usedFallback shouldBe false
        result.target shouldBe PreparedChapterContent.MihonOperational(
            mangaId = 20L,
            chapterId = 30L,
            sourceId = 7L,
        )
        fixture.preparer.lastOption shouldBe option
        fixture.preparer.lastProgress shouldBe fixture.reading.progress
    }

    @Test
    fun `canonical download opens before addon resolution`() = runTest {
        val artifact = CanonicalDownloadArtifact(
            canonicalChapterId = "chapter-1",
            localUri = "content://downloads/chapter-1.cbz",
            format = "CBZ",
            originatingAddonId = AddonId("removed-addon"),
            originatingOptionKey = "old-option",
            completedAt = 100L,
            checksum = null,
        )
        val fixture = fixture(
            preference = ContentPreference("title-1", AddonId("mangadex"), 1L),
            providers = listOf(provider(option("mangadex"))),
            downloadArtifact = artifact,
        )

        val result = fixture.prepare.execute("chapter-1")

        result.shouldBeInstanceOf<CanonicalReaderPreparation.Ready>()
        result.target shouldBe PreparedChapterContent.CanonicalDownload(
            uri = artifact.localUri,
            format = artifact.format,
        )
        fixture.preparer.calls shouldBe 0
    }

    @Test
    fun `first read returns selection state without preparing content`() = runTest {
        val option = option("mangadex")
        val fixture = fixture(
            preference = null,
            providers = listOf(provider(option)),
        )

        val result = fixture.prepare.execute("chapter-1")

        result.shouldBeInstanceOf<CanonicalReaderPreparation.SelectionRequired>()
        result.canonicalTitleId shouldBe "title-1"
        result.canonicalChapterId shouldBe "chapter-1"
        result.options shouldBe listOf(option)
        result.preferredAddonId shouldBe null
        result.preferredUnavailable shouldBe false
        fixture.preparer.calls shouldBe 0
    }

    @Test
    fun `explicitly selected option prepares same canonical chapter without rekeying progress`() = runTest {
        val selected = option("mangafire")
        val fixture = fixture(
            preference = null,
            providers = emptyList(),
        )
        fixture.reading.progress = CanonicalChapterProgress(
            canonicalChapterId = "chapter-1",
            lastPageRead = 8L,
            lastVariantId = null,
            updatedAt = 100L,
        )

        val result = fixture.prepare.execute(
            canonicalChapterId = "chapter-1",
            selectedOption = selected,
        )

        result.shouldBeInstanceOf<CanonicalReaderPreparation.Ready>()
        result.canonicalChapterId shouldBe "chapter-1"
        fixture.preparer.lastOption shouldBe selected
        fixture.preparer.lastProgress?.canonicalChapterId shouldBe "chapter-1"
    }

    @Test
    fun `no content option returns unavailable without preparer call`() = runTest {
        val fixture = fixture(
            preference = null,
            providers = emptyList(),
        )

        fixture.prepare.execute("chapter-1")
            .shouldBeInstanceOf<CanonicalReaderPreparation.Unavailable>()
        fixture.preparer.calls shouldBe 0
    }

    private fun fixture(
        preference: ContentPreference?,
        providers: List<ContentProvider>,
        downloadArtifact: CanonicalDownloadArtifact? = null,
    ): Fixture {
        val readerPreferences = CanonicalReaderPreferences(InMemoryPreferenceStore())
        val resolver = ResolveChapterContent(
            addonRegistry = FakeAddonRegistry(providers),
            contentPreferenceRepository = FakeContentPreferenceRepository(preference),
            readerPreferences = readerPreferences,
            rankContentOptions = RankContentOptions(),
            contentOptionCache = ContentOptionCache(),
            inFlightContentResolution = InFlightContentResolution(),
        )
        val chapters = FakeCanonicalChapterRepository()
        val reading = FakeCanonicalReadingRepository()
        val preparer = FakeChapterContentPreparer()
        val prepare = PrepareCanonicalChapterForReader(
            resolveChapterContent = resolver,
            canonicalChapterRepository = chapters,
            canonicalReadingRepository = reading,
            canonicalDownloadRepository = FakeCanonicalDownloadRepository(downloadArtifact),
            chapterContentPreparer = preparer,
        )
        return Fixture(prepare, reading, preparer)
    }

    private data class Fixture(
        val prepare: PrepareCanonicalChapterForReader,
        val reading: FakeCanonicalReadingRepository,
        val preparer: FakeChapterContentPreparer,
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
        scanlationGroup = "Group",
        releaseDate = 100L,
        delivery = ContentDelivery.Mihon(
            sourceId = 7L,
            mangaId = 20L,
            chapterId = 30L,
        ),
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

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            listOf(chapter).filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(listOf(chapter).filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getById(id: String): CanonicalChapter? = chapter.takeIf { it.id == id }

        override suspend fun getVariantBySourceIdentity(
            sourceId: Long,
            sourceChapterId: String,
        ): ChapterVariant? = null

        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            emptyList()

        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> =
            emptyList()

        override suspend fun upsert(chapter: CanonicalChapter) = Unit
        override suspend fun upsertVariant(variant: ChapterVariant) = Unit
        override suspend fun upsertBatch(
            chapters: List<CanonicalChapter>,
            variants: List<ChapterVariant>,
        ) = Unit
    }

    private class FakeCanonicalDownloadRepository(
        private var artifact: CanonicalDownloadArtifact?,
    ) : CanonicalDownloadRepository {
        override suspend fun get(canonicalChapterId: String): CanonicalDownloadArtifact? =
            artifact?.takeIf { it.canonicalChapterId == canonicalChapterId }

        override suspend fun upsert(artifact: CanonicalDownloadArtifact) {
            this.artifact = artifact
        }

        override suspend fun delete(canonicalChapterId: String) {
            if (artifact?.canonicalChapterId == canonicalChapterId) artifact = null
        }

        override suspend fun deleteOriginMetadata(addonId: AddonId) = Unit
    }

    private class FakeCanonicalReadingRepository : CanonicalReadingRepository {
        var progress: CanonicalChapterProgress? = null

        override suspend fun getProgress(canonicalChapterId: String): CanonicalChapterProgress? =
            progress?.takeIf { it.canonicalChapterId == canonicalChapterId }

        override fun observeProgress(canonicalChapterId: String): Flow<CanonicalChapterProgress?> =
            MutableStateFlow(progress?.takeIf { it.canonicalChapterId == canonicalChapterId })

        override suspend fun getProgressByCanonicalTitleId(
            canonicalTitleId: String,
        ): List<CanonicalChapterProgress> = listOfNotNull(progress)

        override suspend fun upsertProgress(progress: CanonicalChapterProgress) {
            this.progress = progress
        }

        override suspend fun getHistory(canonicalChapterId: String): CanonicalChapterHistory? = null
        override suspend fun recordHistory(update: CanonicalChapterHistoryUpdate) = Unit

        override suspend fun recordCheckpoint(
            progress: CanonicalChapterProgress,
            history: CanonicalChapterHistoryUpdate?,
        ) {
            this.progress = progress
        }
    }

    private class FakeChapterContentPreparer : ChapterContentPreparer {
        var calls = 0
        var lastOption: ContentOption? = null
        var lastProgress: CanonicalChapterProgress? = null

        override suspend fun prepare(
            option: ContentOption,
            progress: CanonicalChapterProgress?,
        ): Result<PreparedChapterContent> {
            calls++
            lastOption = option
            lastProgress = progress
            return Result.success(
                PreparedChapterContent.MihonOperational(
                    mangaId = 20L,
                    chapterId = 30L,
                    sourceId = 7L,
                ),
            )
        }
    }
}
