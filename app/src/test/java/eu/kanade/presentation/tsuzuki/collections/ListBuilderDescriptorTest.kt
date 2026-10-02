package eu.kanade.presentation.tsuzuki.collections

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.collections.capability.CollectionFilterCapability
import tachiyomi.domain.tsuzuki.collections.capability.CollectionPagingCapability
import tachiyomi.domain.tsuzuki.collections.capability.CollectionPagingMode
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderDescriptor
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderScope
import tachiyomi.domain.tsuzuki.collections.capability.CollectionSortCapability
import tachiyomi.domain.tsuzuki.collections.capability.FilterExecutionMode
import tachiyomi.domain.tsuzuki.collections.capability.FilterOption
import tachiyomi.domain.tsuzuki.collections.capability.FilterPlacement
import tachiyomi.domain.tsuzuki.collections.capability.FilterValueSource
import tachiyomi.domain.tsuzuki.collections.capability.MultiValueMode
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortDirection
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.model.SortDirectionMode
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class ListBuilderDescriptorTest {

    @Test
    fun `source choices come from registered descriptors and preserve current legacy provider only`() {
        val descriptors = listOf(
            descriptor("kitsu", quick = listOf(QueryField.STATUS)),
            descriptor("mangaupdates", quick = listOf(QueryField.GENRE)),
        )

        listBuilderProviderIds(descriptors, "kitsu") shouldContainExactly
            listOf("kitsu", "mangaupdates")
        listBuilderProviderIds(descriptors, "legacy") shouldContainExactly
            listOf("kitsu", "mangaupdates", "legacy")
    }

    @Test
    fun `no registered provider never fabricates a fallback source`() {
        listBuilderProviderIds(emptyList(), "") shouldBe emptyList()
        listBuilderProviderIds(emptyList(), "legacy") shouldBe listOf("legacy")
    }

    @Test
    fun `providers expose different quick fields without common denominator filtering`() {
        val kitsu = descriptor(
            "kitsu",
            quick = listOf(QueryField.STATUS, QueryField.WORK_TYPE, QueryField.GENRE),
        )
        val mal = descriptor(
            "mal",
            quick = listOf(QueryField.WORK_TYPE),
        )

        kitsu.visibleFilterFields(FilterPlacement.QUICK) shouldContainExactly
            listOf(QueryField.STATUS, QueryField.WORK_TYPE, QueryField.GENRE)
        mal.visibleFilterFields(FilterPlacement.QUICK) shouldContainExactly
            listOf(QueryField.WORK_TYPE)
    }

    @Test
    fun `fixed provider sort never fabricates reverse direction`() {
        val descriptor = CollectionProviderDescriptor(
            providerId = "mangaupdates",
            displayName = "MangaUpdates",
            scope = CollectionProviderScope.GLOBAL,
            filters = emptyList(),
            sorts = listOf(
                CollectionSortCapability(
                    key = CollectionSortKey.Provider("mangaupdates", "week_pos"),
                    label = "Weekly popularity",
                    directionMode = SortDirectionMode.FIXED_NATIVE,
                    defaultDirection = null,
                ),
            ),
            paging = CollectionPagingCapability(CollectionPagingMode.OFFSET, maxPageSize = 20),
        )

        descriptor.uiSortSelections() shouldBe listOf(
            CollectionSortSelection(
                key = CollectionSortKey.Provider("mangaupdates", "week_pos"),
                direction = null,
            ),
        )
    }

    private fun descriptor(
        id: String,
        quick: List<QueryField>,
    ) = CollectionProviderDescriptor(
        providerId = id,
        displayName = id,
        scope = CollectionProviderScope.GLOBAL,
        filters = quick.mapIndexed { index, field ->
            CollectionFilterCapability(
                id = "filter_$index",
                field = field,
                placement = FilterPlacement.QUICK,
                operators = setOf(QueryOperator.EQUALS),
                execution = setOf(FilterExecutionMode.RESIDUAL_EXACT),
                valueSource = FilterValueSource.Static(
                    listOf(FilterOption("value", "Value", QueryValue.of("VALUE"))),
                ),
                multiValueMode = MultiValueMode.SINGLE,
            )
        },
        sorts = emptyList(),
        paging = CollectionPagingCapability(CollectionPagingMode.OFFSET, maxPageSize = 20),
    )
}
