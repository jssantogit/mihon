package eu.kanade.tachiyomi.data.track

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrackerManagerTest {

    @Test
    fun `retired tracker ids stay reserved and inactive`() {
        val manager = TrackerManager()
        val activeIds = manager.trackers.map(Tracker::id).toSet()

        activeIds shouldBe setOf(1L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)
        TrackerManager.RESERVED_ANILIST_TRACKER_ID shouldBe 2L
        TrackerManager.RESERVED_MANGABAKA_TRACKER_ID shouldBe 11L
        (TrackerManager.RESERVED_ANILIST_TRACKER_ID in activeIds) shouldBe false
        (TrackerManager.RESERVED_MANGABAKA_TRACKER_ID in activeIds) shouldBe false
        manager.get(TrackerManager.RESERVED_ANILIST_TRACKER_ID) shouldBe null
        manager.get(TrackerManager.RESERVED_MANGABAKA_TRACKER_ID) shouldBe null
    }
}
