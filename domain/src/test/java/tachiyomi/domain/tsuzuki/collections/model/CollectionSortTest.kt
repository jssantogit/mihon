package tachiyomi.domain.tsuzuki.collections.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort

// Final Collections V2 integration checkpoint.
class CollectionSortTest {

    @Test
    fun `legacy catalog sorts migrate losslessly to Collection sorts`() {
        val cases = CatalogSort.entries.associateWith(CollectionSortSelection::fromLegacy)

        cases.getValue(CatalogSort.POPULARITY_DESC) shouldBe CollectionSortSelection(
            CollectionSortKey.Standard.POPULARITY,
            CollectionSortDirection.DESC,
        )
        cases.getValue(CatalogSort.POPULARITY_ASC) shouldBe CollectionSortSelection(
            CollectionSortKey.Standard.POPULARITY,
            CollectionSortDirection.ASC,
        )
        cases.getValue(CatalogSort.RATING_DESC) shouldBe CollectionSortSelection(
            CollectionSortKey.Standard.RATING,
            CollectionSortDirection.DESC,
        )
        cases.getValue(CatalogSort.RATING_ASC) shouldBe CollectionSortSelection(
            CollectionSortKey.Standard.RATING,
            CollectionSortDirection.ASC,
        )
        cases.getValue(CatalogSort.UPDATED_DESC) shouldBe CollectionSortSelection(
            CollectionSortKey.Standard.UPDATED,
            CollectionSortDirection.DESC,
        )
        cases.getValue(CatalogSort.RELEVANCE) shouldBe CollectionSortSelection(
            CollectionSortKey.Standard.RELEVANCE,
            direction = null,
        )

        cases.forEach { (legacy, migrated) ->
            migrated.toLegacyCatalogSortOrNull() shouldBe legacy
        }
    }

    @Test
    fun `provider sort stable key round trips through storage parser`() {
        val selection = CollectionSortSelection(
            key = CollectionSortKey.Provider(
                providerId = "mangaupdates",
                nativeId = "month3_pos",
            ),
            direction = null,
        )

        CollectionSortSelection.fromStorage(
            stableKey = selection.stableKey,
            direction = selection.direction?.name,
        ) shouldBe selection
        selection.cacheKey shouldBe "provider.mangaupdates.month3_pos|FIXED"
    }

    @Test
    fun `legacy persisted enum names remain readable`() {
        CollectionSortSelection.fromStorage(
            stableKey = "RATING_DESC",
            direction = null,
        ) shouldBe CollectionSortSelection(
            CollectionSortKey.Standard.RATING,
            CollectionSortDirection.DESC,
        )
    }

    @Test
    fun `unknown non provider sort key fails closed`() {
        shouldThrow<IllegalArgumentException> {
            CollectionSortKey.fromStableId("unknown.rating")
        }
    }
}
