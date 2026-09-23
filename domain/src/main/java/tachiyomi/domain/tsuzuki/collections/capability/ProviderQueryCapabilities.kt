package tachiyomi.domain.tsuzuki.collections.capability

import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

/**
 * Capability declaration for a catalog provider describing which parts of a query AST,
 * sorting modes, and paging mechanisms can be pushed down to the remote service.
 */
interface ProviderQueryCapabilities {
    val providerId: String

    fun canPushPredicate(field: QueryField, operator: QueryOperator, value: QueryValue): Boolean

    /**
     * Returns true only when the provider can represent the complete expression with exact semantics.
     *
     * The default intentionally supports atomic predicates only. Compound boolean expressions must be
     * explicitly advertised by a provider implementation; predicate-level support alone does not prove
     * that AND, OR, or NOT can be represented by the provider query contract.
     */
    fun canPushExpression(expression: QueryExpression): Boolean = when (expression) {
        is QueryExpression.Predicate -> canPushPredicate(
            field = expression.field,
            operator = expression.operator,
            value = expression.value,
        )
        is QueryExpression.All,
        is QueryExpression.Any,
        is QueryExpression.Not,
        -> false
    }

    fun canPushSort(sort: CatalogSort): Boolean

    val supportsOffsetPaging: Boolean

    val maxPageSize: Int?
}
