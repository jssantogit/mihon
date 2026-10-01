package tachiyomi.domain.tsuzuki.source.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository
import tachiyomi.domain.tsuzuki.diagnostics.NoOpStructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.model.MaterializedReadingSource
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceDescriptor
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway

class ResolveCanonicalSourceMangaContentBindingTest {

    @Test
    fun `content binding repairs source artwork when legacy source mappings are absent`() = runTest {
        val titleRepository = mockk<CanonicalTitleRepository>()
        coEvery { titleRepository.getById(TITLE_ID) } returns CanonicalTitle(
            id = TITLE_ID,
            displayTitle = "Boku no Hero Academia",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val mappingRepository = mockk<SourceTitleMappingRepository>()
        coEvery { mappingRepository.getByCanonicalTitleId(TITLE_ID) } returns emptyList()

        val bindingRepository = FakeContentBindingRepository(
            ContentBinding(
                id = "binding-1",
                canonicalTitleId = TITLE_ID,
                addonId = AddonId("mihon"),
                providerTitleKey = "10:/boku-no-hero",
                matchConfidence = 1.0,
                verifiedByUser = true,
                availability = ContentBindingAvailability.AVAILABLE,
                runtimePayload = byteArrayOf(1, 2, 3),
                createdAt = 1L,
                updatedAt = 2L,
            ),
        )
        val gateway = object : ReadingSourceGateway {
            override suspend fun listInstalled(language: String): List<ReadingSourceDescriptor> = emptyList()

            override suspend fun search(
                sourceId: Long,
                query: String,
            ): Result<List<ReadingSourceCandidate>> = Result.success(emptyList())

            override suspend fun restoreMaterializedCandidate(
                runtimePayload: ByteArray,
                fallbackTitle: String,
            ): Result<ReadingSourceCandidate> = Result.success(
                ReadingSourceCandidate(
                    sourceId = 10L,
                    sourceName = "Bound source",
                    language = "pt-BR",
                    sourceUrl = "/boku-no-hero",
                    title = fallbackTitle,
                    thumbnailUrl = null,
                    author = null,
                    artist = null,
                    description = null,
                    genres = null,
                    status = 0L,
                ),
            )

            override suspend fun getDetails(
                candidate: ReadingSourceCandidate,
            ): Result<ReadingSourceCandidate> = Result.success(
                candidate.copy(
                    thumbnailUrl = "https://source.example/boku.jpg",
                    description = "details",
                ),
            )

            override suspend fun materialize(
                candidate: ReadingSourceCandidate,
            ): Result<MaterializedReadingSource> = error("Not used")
        }
        val networkToLocal = mockk<NetworkToLocalManga>()
        coEvery { networkToLocal(any<Manga>()) } answers {
            (args[0] as Manga).copy(id = 99L)
        }
        val mangaRepository = mockk<MangaRepository>()

        val resolved = ResolveCanonicalSourceManga(
            canonicalTitleRepository = titleRepository,
            sourceTitleMappingRepository = mappingRepository,
            mangaRepository = mangaRepository,
            networkToLocalManga = networkToLocal,
            readingSourceGateway = gateway,
            contentBindingRepository = bindingRepository,
            structuredDiagnostics = NoOpStructuredDiagnosticRecorder,
        ).execute(TITLE_ID)

        resolved?.thumbnailUrl shouldBe "https://source.example/boku.jpg"
        resolved?.source shouldBe 10L
        resolved?.url shouldBe "/boku-no-hero"
    }

    private class FakeContentBindingRepository(
        private val binding: ContentBinding,
    ) : ContentBindingRepository {
        override suspend fun get(
            canonicalTitleId: String,
            addonId: AddonId,
        ): ContentBinding? = binding.takeIf {
            it.canonicalTitleId == canonicalTitleId && it.addonId == addonId
        }

        override suspend fun getByTitle(canonicalTitleId: String): List<ContentBinding> =
            listOf(binding).filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun upsert(binding: ContentBinding) = Unit

        override suspend fun markUnavailable(bindingId: String, updatedAt: Long) = Unit
    }

    private companion object {
        const val TITLE_ID = "title"
    }
}
