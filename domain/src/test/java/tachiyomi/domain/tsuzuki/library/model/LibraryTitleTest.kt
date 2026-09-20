package tachiyomi.domain.tsuzuki.library.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceRepresentation

class LibraryTitleTest {

    @Test
    fun `canonical title id is the LibraryTitle identity`() {
        val libraryTitle = LibraryTitle(
            title = title("canonical-1"),
            entry = entry("canonical-1"),
        )

        libraryTitle.id shouldBe "canonical-1"
    }

    @Test
    fun `membership must belong to the same canonical title`() {
        shouldThrow<IllegalArgumentException> {
            LibraryTitle(
                title = title("canonical-1"),
                entry = entry("canonical-2"),
            )
        }
    }

    @Test
    fun `source representations must belong to the same canonical title`() {
        shouldThrow<IllegalArgumentException> {
            LibraryTitle(
                title = title("canonical-1"),
                entry = entry("canonical-1"),
                sources = listOf(sourceRepresentation(canonicalTitleId = "canonical-2")),
            )
        }
    }

    @Test
    fun `mihon manga id is only a nullable local operational reference`() {
        val materialized = sourceRepresentation(
            canonicalTitleId = "canonical-1",
            localMihonMangaId = 42L,
        )
        val portable = sourceRepresentation(
            canonicalTitleId = "canonical-1",
            localMihonMangaId = null,
        )

        materialized.localMihonMangaId shouldBe 42L
        portable.localMihonMangaId shouldBe null
        materialized.key shouldBe SourceRepresentation.Key(123L, "/manga/title")
        portable.key shouldBe materialized.key
    }

    private fun title(id: String) = CanonicalTitle(
        id = id,
        displayTitle = "Title",
        identityState = CanonicalIdentityState.RESOLVED,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun entry(id: String) = CanonicalLibraryEntry(
        canonicalTitleId = id,
        status = LibraryStatus.READING,
        favorite = true,
        addedAt = 100L,
        updatedAt = 100L,
    )

    private fun sourceRepresentation(
        canonicalTitleId: String,
        localMihonMangaId: Long? = null,
    ) = SourceRepresentation(
        id = "mapping-1",
        canonicalTitleId = canonicalTitleId,
        mihonMangaId = localMihonMangaId,
        sourceId = 123L,
        sourceUrl = "/manga/title",
        language = "en",
        matchConfidence = 1.0,
        verifiedByUser = true,
        availability = SourceMappingAvailability.AVAILABLE,
        preferredOverride = false,
        createdAt = 100L,
        updatedAt = 100L,
    )
}
