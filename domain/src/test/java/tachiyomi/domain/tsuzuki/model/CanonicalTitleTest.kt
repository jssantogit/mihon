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
}
