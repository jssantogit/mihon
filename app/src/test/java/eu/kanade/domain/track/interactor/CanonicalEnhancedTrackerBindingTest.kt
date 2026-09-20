package eu.kanade.domain.track.interactor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.tsuzuki.reader.model.CanonicalTrackerBindingResolution

class CanonicalEnhancedTrackerBindingTest {

    @Test
    fun `bound and conflicting tracker ids are unavailable for automatic binding`() {
        val resolution = CanonicalTrackerBindingResolution(
            tracks = listOf(track(trackerId = 100L)),
            conflictingTrackerIds = setOf(200L),
        )

        canonicalTrackerIdsUnavailableForAutoBind(resolution) shouldBe setOf(100L, 200L)
    }

    private fun track(trackerId: Long) = Track(
        id = 1L,
        mangaId = 11L,
        trackerId = trackerId,
        remoteId = 900L,
        libraryId = null,
        title = "Track",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 0L,
        score = 0.0,
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )
}
