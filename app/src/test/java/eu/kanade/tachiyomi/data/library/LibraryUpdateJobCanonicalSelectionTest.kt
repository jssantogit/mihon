package eu.kanade.tachiyomi.data.library

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceRepresentation

class LibraryUpdateJobCanonicalSelectionTest {

    @Test
    fun `operational representations are eligible and deterministically prioritized`() {
        val sources = listOf(
            source("fallback", 1L, 11L, preferred = false, verified = false),
            source("verified", 2L, 22L, preferred = false, verified = true),
            source("preferred", 3L, 33L, preferred = true, verified = false),
            source("unavailable", 4L, 44L, availability = SourceMappingAvailability.UNAVAILABLE),
            source("not-materialized", 5L, null),
        )

        selectOperationalSourceRepresentations(sources)
            .map { it.id }
            .shouldContainExactly("preferred", "verified", "fallback")
    }

    private fun source(
        id: String,
        sourceId: Long,
        mihonMangaId: Long?,
        preferred: Boolean = false,
        verified: Boolean = false,
        availability: SourceMappingAvailability = SourceMappingAvailability.AVAILABLE,
    ) = SourceRepresentation(
        id = id,
        canonicalTitleId = "title-1",
        mihonMangaId = mihonMangaId,
        sourceId = sourceId,
        sourceUrl = "/$id",
        language = "en",
        matchConfidence = 1.0,
        verifiedByUser = verified,
        availability = availability,
        preferredOverride = preferred,
        createdAt = 100L,
        updatedAt = 100L,
    )
}
