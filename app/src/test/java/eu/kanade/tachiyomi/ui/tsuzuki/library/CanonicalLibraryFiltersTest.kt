package eu.kanade.tachiyomi.ui.tsuzuki.library

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.library.model.ExternalLibraryMembership
import tachiyomi.domain.tsuzuki.library.model.UnifiedLibraryTitle
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus

class CanonicalLibraryFiltersTest {

    @Test
    fun `status is evaluated inside selected provider while format intersects it`() {
        val items = listOf(
            card(
                id = "one",
                originStatuses = mapOf(
                    "tsuzuki" to setOf(LibraryStatus.READING),
                    "mal" to setOf(LibraryStatus.PLANNING),
                ),
                format = CatalogItemFormat.MANHWA,
            ),
            card(
                id = "two",
                originStatuses = mapOf("mal" to setOf(LibraryStatus.READING)),
                format = CatalogItemFormat.MANGA,
            ),
        )

        val result = filterCanonicalLibraryCards(
            items = items,
            filters = CanonicalLibraryFilterState(
                status = LibraryStatus.PLANNING,
                origin = "mal",
                formats = setOf(CatalogItemFormat.MANHWA),
            ),
        )

        result.map { it.canonicalTitleId } shouldContainExactly listOf("one")
    }

    @Test
    fun `all origins matches status from any membership while provider filter stays scoped`() {
        val item = card(
            id = "one",
            originStatuses = mapOf(
                "tsuzuki" to setOf(LibraryStatus.READING),
                "mal" to setOf(LibraryStatus.PLANNING),
            ),
            format = CatalogItemFormat.MANGA,
        )

        filterCanonicalLibraryCards(
            items = listOf(item),
            filters = CanonicalLibraryFilterState(status = LibraryStatus.READING),
        ).map { it.canonicalTitleId } shouldContainExactly listOf("one")

        filterCanonicalLibraryCards(
            items = listOf(item),
            filters = CanonicalLibraryFilterState(
                status = LibraryStatus.READING,
                origin = "mal",
            ),
        ) shouldContainExactly emptyList()
    }

    @Test
    fun `provider list filter is scoped to the selected origin`() {
        val items = listOf(
            card(
                id = "custom",
                originStatuses = mapOf("mangaupdates" to emptySet()),
                originListKeys = mapOf("mangaupdates" to setOf("mangaupdates:list:99")),
                format = CatalogItemFormat.MANGA,
            ),
            card(
                id = "other",
                originStatuses = mapOf("mangaupdates" to setOf(LibraryStatus.READING)),
                originListKeys = mapOf("mangaupdates" to setOf("mangaupdates:list:1")),
                format = CatalogItemFormat.MANGA,
            ),
        )

        val result = filterCanonicalLibraryCards(
            items = items,
            filters = CanonicalLibraryFilterState(
                origin = "mangaupdates",
                listKey = "mangaupdates:list:99",
            ),
        )

        result.map { it.canonicalTitleId } shouldContainExactly listOf("custom")
    }

    @Test
    fun `provider list options expose named custom lists but hide status mirrors`() {
        val items = listOf(
            card(
                id = "mal",
                originStatuses = mapOf("mal" to setOf(LibraryStatus.PLANNING)),
                originListKeys = mapOf("mal" to setOf("mal:status:plan_to_read")),
                originListOptions = mapOf(
                    "mal" to setOf(
                        CanonicalLibraryListFilterOption(
                            key = "mal:status:plan_to_read",
                            title = "Plan to Read",
                            selectionGroup = "mal:status",
                        ),
                    ),
                ),
                format = CatalogItemFormat.MANGA,
            ),
            card(
                id = "mu",
                originStatuses = mapOf("mangaupdates" to emptySet()),
                originListKeys = mapOf("mangaupdates" to setOf("mangaupdates:list:99")),
                originListOptions = mapOf(
                    "mangaupdates" to setOf(
                        CanonicalLibraryListFilterOption(
                            key = "mangaupdates:list:99",
                            title = "Favorites",
                            selectionGroup = "mangaupdates:list",
                        ),
                    ),
                ),
                format = CatalogItemFormat.MANGA,
            ),
        )

        availableProviderListFilters(items, "mal") shouldContainExactly emptyList()
        availableProviderListFilters(items, "mangaupdates")
            .map { it.key to it.title } shouldContainExactly listOf("mangaupdates:list:99" to "Favorites")
    }

    @Test
    fun `library card uses provider artwork and falls back to local manga cover`() {
        val title = CanonicalTitle(
            id = "work",
            displayTitle = "Monster",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val external = UnifiedLibraryTitle(
            title = title,
            localEntry = null,
            externalMemberships = listOf(
                ExternalLibraryMembership(
                    canonicalTitleId = "work",
                    provider = "mal",
                    externalId = "42",
                    listKey = "mal:status:reading",
                    status = LibraryStatus.READING,
                    remoteStatus = "reading",
                    progress = 1.0,
                    score = null,
                    syncedAt = 1L,
                    coverUrl = "https://cdn.example/monster.jpg",
                ),
            ),
        )
        external.toCardModel(
            progress = emptyList(),
            localCoverUrl = "file://local-cover.jpg",
        ).coverUrl shouldBe "https://cdn.example/monster.jpg"

        val local = UnifiedLibraryTitle(
            title = title,
            localEntry = CanonicalLibraryEntry(
                canonicalTitleId = "work",
                status = LibraryStatus.READING,
                favorite = true,
                addedAt = 1L,
                updatedAt = 1L,
            ),
            externalMemberships = emptyList(),
        )
        local.toCardModel(
            progress = emptyList(),
            localCoverUrl = "file://local-cover.jpg",
        ).coverUrl shouldBe "file://local-cover.jpg"
    }

    private fun card(
        id: String,
        originStatuses: Map<String, Set<LibraryStatus>>,
        format: CatalogItemFormat,
        originListKeys: Map<String, Set<String>> = emptyMap(),
        originListOptions: Map<String, Set<CanonicalLibraryListFilterOption>> = emptyMap(),
    ) = CanonicalLibraryCardModel(
        canonicalTitleId = id,
        title = id,
        status = originStatuses.values.flatten().firstOrNull() ?: LibraryStatus.PLANNING,
        categories = emptyList(),
        readingState = CanonicalLibraryReadingState.NOT_STARTED,
        originStatuses = originStatuses,
        originListKeys = originListKeys,
        originListOptions = originListOptions,
        format = format,
        hasLocalMembership = "tsuzuki" in originStatuses,
    )
}
