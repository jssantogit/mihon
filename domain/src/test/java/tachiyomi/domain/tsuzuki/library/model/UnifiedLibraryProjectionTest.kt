package tachiyomi.domain.tsuzuki.library.model

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus

class UnifiedLibraryProjectionTest {

    @Test
    fun `projection deduplicates local and remote memberships by canonical title`() {
        val result = projectUnifiedLibrary(
            titles = listOf(
                title("one", "One"),
                title("two", "Two"),
                title("orphan", "Orphan"),
            ),
            localEntries = listOf(
                local("one", LibraryStatus.READING),
            ),
            externalMemberships = listOf(
                ExternalLibraryMembership(
                    canonicalTitleId = "one",
                    provider = "mal",
                    externalId = "101",
                    listKey = "mal:status:plan_to_read",
                    status = LibraryStatus.PLANNING,
                    remoteStatus = "plan_to_read",
                    progress = 0.0,
                    score = 0.0,
                    syncedAt = 500L,
                ),
                ExternalLibraryMembership(
                    canonicalTitleId = "two",
                    provider = "mal",
                    externalId = "202",
                    listKey = "mal:status:completed",
                    status = LibraryStatus.COMPLETED,
                    remoteStatus = "completed",
                    progress = 12.0,
                    score = 8.0,
                    syncedAt = 500L,
                ),
            ),
            formatObservations = listOf(
                TitleFormatObservation("one", "mal", CatalogItemFormat.MANGA, 500L),
                TitleFormatObservation("two", "mal", CatalogItemFormat.MANHWA, 500L),
            ),
        )

        result.map { it.title.id } shouldContainExactlyInAnyOrder listOf("one", "two")

        val one = result.single { it.title.id == "one" }
        one.origins shouldContainExactlyInAnyOrder setOf("tsuzuki", "mal")
        one.statusesForOrigin("tsuzuki") shouldBe setOf(LibraryStatus.READING)
        one.statusesForOrigin("mal") shouldBe setOf(LibraryStatus.PLANNING)
        one.format shouldBe CatalogItemFormat.MANGA

        val two = result.single { it.title.id == "two" }
        two.localEntry shouldBe null
        two.origins shouldBe setOf("mal")
        two.format shouldBe CatalogItemFormat.MANHWA
    }

    private fun title(id: String, name: String) = CanonicalTitle(
        id = id,
        displayTitle = name,
        identityState = CanonicalIdentityState.RESOLVED,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun local(id: String, status: LibraryStatus) = CanonicalLibraryEntry(
        canonicalTitleId = id,
        status = status,
        favorite = true,
        addedAt = 100L,
        updatedAt = 100L,
    )
}
