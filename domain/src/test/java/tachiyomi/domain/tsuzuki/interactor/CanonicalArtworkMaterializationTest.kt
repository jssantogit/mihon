package tachiyomi.domain.tsuzuki.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.artwork.model.TitleArtworkObservation
import tachiyomi.domain.tsuzuki.artwork.repository.TitleArtworkRepository
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class CanonicalArtworkMaterializationTest {

    @Test
    fun `catalog materialization persists artwork for any provider identity`() = runTest {
        val titleRepository = FakeCanonicalTitleRepository()
        val artworkRepository = FakeTitleArtworkRepository()
        var nextId = 0
        val materializer = MaterializeCanonicalTitleFromCatalog(
            materializeCanonicalTitle = MaterializeCanonicalTitle(
                repository = titleRepository,
                idFactory = { "title-" + (++nextId) },
                clock = { 100L },
            ),
            reportedChapterCountRepository = null,
            clock = { 200L },
            titleFormatObservationRepository = null,
            titleArtworkRepository = artworkRepository,
        )

        val kitsu = materializer.execute(
            CatalogItem(
                provider = "kitsu",
                providerId = "k1",
                title = "Dandadan",
                coverUrl = "https://kitsu/cover.jpg",
                bannerUrl = "https://kitsu/banner.jpg",
            ),
        )
        val mal = materializer.execute(
            CatalogItem(
                provider = "mal",
                providerId = "m1",
                title = "Monster",
                coverUrl = "https://mal/cover.jpg",
            ),
        )

        artworkRepository.getByTitle(kitsu.id).single() shouldBe TitleArtworkObservation(
            canonicalTitleId = kitsu.id,
            provider = "kitsu",
            coverUrl = "https://kitsu/cover.jpg",
            bannerUrl = "https://kitsu/banner.jpg",
            updatedAt = 200L,
        )
        artworkRepository.getByTitle(mal.id).single() shouldBe TitleArtworkObservation(
            canonicalTitleId = mal.id,
            provider = "mal",
            coverUrl = "https://mal/cover.jpg",
            bannerUrl = null,
            updatedAt = 200L,
        )
    }

    private class FakeTitleArtworkRepository : TitleArtworkRepository {
        private val values = MutableStateFlow(emptyList<TitleArtworkObservation>())

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

    private class FakeCanonicalTitleRepository : CanonicalTitleRepository {
        private val titles = mutableMapOf<String, CanonicalTitle>()
        private val identities = mutableListOf<ExternalIdentity>()

        override suspend fun getById(id: String): CanonicalTitle? = titles[id]

        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> =
            MutableStateFlow(titles[id])

        override suspend fun getByExternalIdentity(
            provider: String,
            externalId: String,
        ): CanonicalTitle? = identities
            .firstOrNull { it.provider == provider && it.externalId == externalId }
            ?.canonicalTitleId
            ?.let(titles::get)

        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle {
            getByExternalIdentity(identity.provider, identity.externalId)?.let { return it }
            titles[title.id] = title
            identities += identity
            return title
        }

        override suspend fun insert(title: CanonicalTitle) {
            titles[title.id] = title
        }

        override suspend fun addExternalIdentity(identity: ExternalIdentity) {
            identities += identity
        }
    }
}
