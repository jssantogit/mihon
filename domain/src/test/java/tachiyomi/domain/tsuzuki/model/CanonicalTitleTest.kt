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
