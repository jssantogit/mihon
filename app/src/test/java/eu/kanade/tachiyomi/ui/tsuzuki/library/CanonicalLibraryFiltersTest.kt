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

    private fun card(
        id: String,
        originStatuses: Map<String, Set<LibraryStatus>>,
        format: CatalogItemFormat,
    ) = CanonicalLibraryCardModel(
        canonicalTitleId = id,
        title = id,
        status = originStatuses.values.flatten().first(),
        categories = emptyList(),
        readingState = CanonicalLibraryReadingState.NOT_STARTED,
        originStatuses = originStatuses,
        format = format,
        hasLocalMembership = "tsuzuki" in originStatuses,
    )
}
