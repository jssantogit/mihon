package tachiyomi.domain.tsuzuki.collections.capability

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortDirection
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.model.SortDirectionMode
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class CollectionProviderSwitchPlannerTest {

    @Test
    fun `lossless switch preserves supported standard filters and sort`() {
        val status = predicate(QueryField.STATUS, "ONGOING")
        val type = predicate(QueryField.WORK_TYPE, "MANGA")
        val query = QueryExpression.All(status, type)
        val sort = CollectionSortSelection(
            CollectionSortKey.Standard.POPULARITY,
            CollectionSortDirection.DESC,
        )

        val result = CollectionProviderSwitchPlanner.plan(
            sourceProviderId = "kitsu",
            target = targetCapabilities(),
            query = query,
            sort = sort,
        ).shouldBeInstanceOf<CollectionProviderSwitchPlan.Ready>()

        result.query shouldBe query.normalize()
        result.sort shouldBe sort
        result.removedExpressions shouldBe emptyList()
        result.removedSort shouldBe null
        result.isLossless shouldBe true
    }

    @Test
    fun `unsupported standard filter is removed and requires confirmation`() {
        val status = predicate(QueryField.STATUS, "ONGOING")
        val score = QueryExpression.Predicate(
            field = QueryField.SCORE,
            operator = QueryOperator.GREATER_OR_EQUAL,
            value = QueryValue.of(8.0),
        )

        val result = CollectionProviderSwitchPlanner.plan(
            sourceProviderId = "kitsu",
            target = targetCapabilities(),
            query = QueryExpression.All(status, score),
            sort = CollectionSortSelection.DEFAULT,
        ).shouldBeInstanceOf<CollectionProviderSwitchPlan.Ready>()

        result.query shouldBe status
        result.removedExpressions shouldBe listOf(score)
        result.requiresConfirmation shouldBe true
    }

    @Test
    fun `provider custom filter does not cross providers`() {
        val custom = QueryExpression.Predicate(
            field = QueryField.Custom("hikka.only_translated"),
            operator = QueryOperator.EQUALS,
            value = QueryValue.of(true),
        )

        val result = CollectionProviderSwitchPlanner.plan(
            sourceProviderId = "hikka",
            target = targetCapabilities(),
            query = custom,
            sort = CollectionSortSelection.DEFAULT,
        ).shouldBeInstanceOf<CollectionProviderSwitchPlan.Ready>()

        result.query shouldBe null
        result.removedExpressions shouldBe listOf(custom)
        result.requiresConfirmation shouldBe true
    }

    @Test
    fun `provider native sort does not cross providers`() {
        val sort = CollectionSortSelection(
            key = CollectionSortKey.Provider("hikka", "native_score"),
            direction = CollectionSortDirection.DESC,
        )

        val result = CollectionProviderSwitchPlanner.plan(
            sourceProviderId = "hikka",
            target = targetCapabilities(),
            query = null,
            sort = sort,
        ).shouldBeInstanceOf<CollectionProviderSwitchPlan.Ready>()

        result.sort shouldBe null
        result.removedSort shouldBe sort
        result.requiresConfirmation shouldBe true
    }

    @Test
    fun `complex ANY query blocks visual provider switching`() {
        val query = QueryExpression.Any(
            predicate(QueryField.STATUS, "ONGOING"),
            predicate(QueryField.WORK_TYPE, "MANGA"),
        )

        val result = CollectionProviderSwitchPlanner.plan(
            sourceProviderId = "kitsu",
            target = targetCapabilities(),
            query = query,
            sort = CollectionSortSelection.DEFAULT,
        ).shouldBeInstanceOf<CollectionProviderSwitchPlan.Blocked>()

        result.reason shouldBe CollectionProviderSwitchBlockReason.COMPLEX_QUERY
    }

    @Test
    fun `negated term survives only when target exposes include exclude semantics`() {
        val genre = QueryExpression.Predicate(
            field = QueryField.GENRE,
            operator = QueryOperator.CONTAINS,
            value = QueryValue.of("Romance"),
        )
        val query = QueryExpression.Not(genre)

        val supported = CollectionProviderSwitchPlanner.plan(
            sourceProviderId = "mangaupdates",
            target = targetCapabilities(includeGenreExclusion = true),
            query = query,
            sort = CollectionSortSelection.DEFAULT,
        ).shouldBeInstanceOf<CollectionProviderSwitchPlan.Ready>()
        supported.query shouldBe query

        val unsupported = CollectionProviderSwitchPlanner.plan(
            sourceProviderId = "mangaupdates",
            target = targetCapabilities(includeGenreExclusion = false),
            query = query,
            sort = CollectionSortSelection.DEFAULT,
        ).shouldBeInstanceOf<CollectionProviderSwitchPlan.Ready>()
        unsupported.query shouldBe null
        unsupported.removedExpressions shouldBe listOf(query)
    }

    private fun targetCapabilities(
        includeGenreExclusion: Boolean = false,
    ): ProviderQueryCapabilities {
        val filters = buildList {
            add(
                staticFilter(
                    id = "status",
                    field = QueryField.STATUS,
                    values = listOf("ONGOING"),
                ),
            )
            add(
                staticFilter(
                    id = "type",
                    field = QueryField.WORK_TYPE,
                    values = listOf("MANGA"),
                ),
            )
            add(
                CollectionFilterCapability(
                    id = "genre",
                    field = QueryField.GENRE,
                    placement = FilterPlacement.ADVANCED,
                    operators = setOf(QueryOperator.CONTAINS),
                    execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                    valueSource = FilterValueSource.RemoteLookup("target.genres"),
                    multiValueMode = if (includeGenreExclusion) {
                        MultiValueMode.INCLUDE_EXCLUDE
                    } else {
                        MultiValueMode.ALL
                    },
                ),
            )
        }

        val descriptor = CollectionProviderDescriptor(
            providerId = "target",
            displayName = "Target",
            scope = CollectionProviderScope.GLOBAL,
            filters = filters,
            sorts = listOf(
                CollectionSortCapability(
                    key = CollectionSortKey.Standard.POPULARITY,
                    label = "Popularity",
                    directionMode = SortDirectionMode.ASC_DESC,
                    defaultDirection = CollectionSortDirection.DESC,
                ),
            ),
            paging = CollectionPagingCapability(
                mode = CollectionPagingMode.OFFSET,
                maxPageSize = 20,
                preferredPageSize = 20,
            ),
        )

        return object : ProviderQueryCapabilities {
            override val descriptor: CollectionProviderDescriptor = descriptor

            override fun canPushPredicate(
                field: QueryField,
                operator: QueryOperator,
                value: QueryValue,
            ): Boolean {
                val predicate = QueryExpression.Predicate(field, operator, value)
                return descriptor.supports(predicate, FilterExecutionMode.REMOTE_EXACT)
            }

            override fun canPushExpression(expression: QueryExpression): Boolean = when (expression) {
                is QueryExpression.Predicate -> canPushPredicate(
                    expression.field,
                    expression.operator,
                    expression.value,
                )
                is QueryExpression.All -> expression.expressions.all(::canPushExpression)
                is QueryExpression.Not -> {
                    val predicate = expression.expression as? QueryExpression.Predicate ?: return false
                    descriptor.filters.any { capability ->
                        capability.accepts(predicate, FilterExecutionMode.REMOTE_EXACT) &&
                            capability.multiValueMode == MultiValueMode.INCLUDE_EXCLUDE
                    }
                }
                is QueryExpression.Any -> false
            }

            override fun canPushSort(sort: CollectionSortSelection): Boolean = descriptor.supports(sort)
        }
    }

    private fun staticFilter(
        id: String,
        field: QueryField,
        values: List<String>,
    ) = CollectionFilterCapability(
        id = id,
        field = field,
        placement = FilterPlacement.QUICK,
        operators = setOf(QueryOperator.EQUALS),
        execution = setOf(FilterExecutionMode.REMOTE_EXACT),
        valueSource = FilterValueSource.Static(
            values.map { value ->
                FilterOption(
                    id = value.lowercase(),
                    label = value,
                    value = QueryValue.of(value),
                )
            },
        ),
        multiValueMode = MultiValueMode.SINGLE,
    )

    private fun predicate(
        field: QueryField,
        value: String,
    ) = QueryExpression.Predicate(
        field = field,
        operator = QueryOperator.EQUALS,
        value = QueryValue.of(value),
    )
}
