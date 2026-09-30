package eu.kanade.tachiyomi.ui.tsuzuki.library

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
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

    private fun card(
        id: String,
        originStatuses: Map<String, Set<LibraryStatus>>,
        format: CatalogItemFormat,
        originListKeys: Map<String, Set<String>> = emptyMap(),
    ) = CanonicalLibraryCardModel(
        canonicalTitleId = id,
        title = id,
        status = originStatuses.values.flatten().first(),
        categories = emptyList(),
        readingState = CanonicalLibraryReadingState.NOT_STARTED,
        originStatuses = originStatuses,
        originListKeys = originListKeys,
        format = format,
        hasLocalMembership = "tsuzuki" in originStatuses,
    )
}
