package tachiyomi.domain.tsuzuki.artwork

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.artwork.model.TitleArtworkObservation
import tachiyomi.domain.tsuzuki.artwork.repository.TitleArtworkRepository

class ResolveCanonicalArtworkTest {

    // Full-CI anchor for the canonical artwork regression suite.

    // Canonical artwork is durable shared state; validate persistence, source fallback, and UI together.

    @Test
    fun `provider precedence resolves cover and banner independently with provenance`() = runTest {
        val repository = FakeTitleArtworkRepository(
            listOf(
                observation(
                    provider = "mal",
                    coverUrl = "https://mal/cover.jpg",
                    bannerUrl = "https://mal/banner.jpg",
                ),
                observation(
                    provider = "kitsu",
                    coverUrl = "https://kitsu/cover.jpg",
                    bannerUrl = null,
                ),
                observation(
                    provider = "custom",
                    coverUrl = "https://custom/cover.jpg",
                    bannerUrl = "https://custom/banner.jpg",
                ),
            ),
        )

        val resolved = ResolveCanonicalArtwork(repository).execute(TITLE_ID)

        resolved?.coverUrl shouldBe "https://kitsu/cover.jpg"
        resolved?.coverProvider shouldBe "kitsu"
        resolved?.bannerUrl shouldBe "https://mal/banner.jpg"
        resolved?.bannerProvider shouldBe "mal"
    }

    @Test
    fun `blank artwork observations do not produce canonical artwork`() = runTest {
        val repository = FakeTitleArtworkRepository(
            listOf(
                observation(
                    provider = "kitsu",
                    coverUrl = " ",
                    bannerUrl = null,
                ),
            ),
        )

        ResolveCanonicalArtwork(repository).execute(TITLE_ID) shouldBe null
    }

    private fun observation(
        provider: String,
        coverUrl: String?,
        bannerUrl: String?,
    ) = TitleArtworkObservation(
        canonicalTitleId = TITLE_ID,
        provider = provider,
        coverUrl = coverUrl,
        bannerUrl = bannerUrl,
        updatedAt = 1L,
    )

    private class FakeTitleArtworkRepository(
        initial: List<TitleArtworkObservation>,
    ) : TitleArtworkRepository {
        private val values = MutableStateFlow(initial)

        override fun observeAll(): Flow<List<TitleArtworkObservation>> = values

        override suspend fun getByTitle(canonicalTitleId: String): List<TitleArtworkObservation> =
            values.value.filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun upsert(observation: TitleArtworkObservation) {
            values.value = values.value
                .filterNot {
                    it.canonicalTitleId == observation.canonicalTitleId &&
                        it.provider == observation.provider
                } + observation
        }
    }

    private companion object {
        const val TITLE_ID = "canonical-title"
    }
}
