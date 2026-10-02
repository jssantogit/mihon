package tachiyomi.domain.tsuzuki.collections.capability

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortDirection
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.SortDirectionMode
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class CollectionProviderDescriptorTest {

    private val statusFilter = CollectionFilterCapability(
        id = "status",
        field = QueryField.STATUS,
        placement = FilterPlacement.QUICK,
        operators = setOf(QueryOperator.EQUALS),
        execution = setOf(FilterExecutionMode.REMOTE_EXACT),
        valueSource = FilterValueSource.Static(
            listOf(FilterOption("ongoing", "Ongoing", QueryValue.of("ONGOING"))),
        ),
        multiValueMode = MultiValueMode.SINGLE,
    )

    @Test
    fun `descriptor reports executable field operator pairs`() {
        val descriptor = descriptor(filters = listOf(statusFilter))

        descriptor.supports(
            QueryField.STATUS,
            QueryOperator.EQUALS,
            FilterExecutionMode.REMOTE_EXACT,
        ) shouldBe true
        descriptor.supports(
            QueryField.STATUS,
            QueryOperator.CONTAINS,
            FilterExecutionMode.REMOTE_EXACT,
        ) shouldBe false
    }

    @Test
    fun `provider custom fields must be namespaced to provider`() {
        shouldThrow<IllegalArgumentException> {
            descriptor(
                filters = listOf(
                    statusFilter.copy(
                        id = "translated",
                        field = QueryField.Custom("hikka.only_translated"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `provider sort must belong to declaring provider`() {
        shouldThrow<IllegalArgumentException> {
            descriptor(
                sorts = listOf(
                    CollectionSortCapability(
                        key = CollectionSortKey.Provider("hikka", "native_score"),
                        label = "Hikka Score",
                        directionMode = SortDirectionMode.ASC_DESC,
                        defaultDirection = CollectionSortDirection.DESC,
                    ),
                ),
            )
        }
    }

    @Test
    fun `duplicate filter ids fail closed`() {
        shouldThrow<IllegalArgumentException> {
            descriptor(filters = listOf(statusFilter, statusFilter))
        }
    }

    private fun descriptor(
        filters: List<CollectionFilterCapability> = emptyList(),
        sorts: List<CollectionSortCapability> = emptyList(),
    ) = CollectionProviderDescriptor(
        providerId = "kitsu",
        displayName = "Kitsu",
        scope = CollectionProviderScope.GLOBAL,
        filters = filters,
        sorts = sorts,
        paging = CollectionPagingCapability(
            mode = CollectionPagingMode.OFFSET,
            maxPageSize = 20,
            preferredPageSize = 20,
        ),
    )
}
