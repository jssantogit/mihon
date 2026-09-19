package tachiyomi.domain.tsuzuki.collections.query

/**
 * Deterministic AST normalizer.
 *
 * Enforces canonical representation across equivalent query structures:
 * - Flattens nested ALL inside ALL and nested ANY inside ANY.
 * - Deterministically sorts commutative children of ALL and ANY.
 * - Eliminates exact duplicate expressions.
 * - Sorts and deduplicates values within IN predicates.
 * - Recursively normalizes children through NOT expressions.
 * - Adheres strictly to conservative normalization: no De Morgan or distributive expansion.
 */
object QueryNormalizer {

    fun normalize(expression: QueryExpression): QueryExpression = when (expression) {
        is QueryExpression.Predicate -> normalizePredicate(expression)
        is QueryExpression.Not -> QueryExpression.Not(normalize(expression.expression))
        is QueryExpression.All -> normalizeAll(expression)
        is QueryExpression.Any -> normalizeAny(expression)
    }

    private fun normalizePredicate(predicate: QueryExpression.Predicate): QueryExpression.Predicate {
        return if (predicate.operator == QueryOperator.IN && predicate.value is QueryValue.ListValue) {
            val normalizedValues = predicate.value.values
                .distinct()
                .sorted()
            predicate.copy(value = QueryValue.ListValue(normalizedValues))
        } else {
            predicate
        }
    }

    private fun normalizeAll(all: QueryExpression.All): QueryExpression.All {
        val flattened = mutableListOf<QueryExpression>()
        fun collect(expr: QueryExpression) {
            val normalized = normalize(expr)
            if (normalized is QueryExpression.All) {
                for (child in normalized.expressions) {
                    collect(child)
                }
            } else {
                flattened.add(normalized)
            }
        }
        for (child in all.expressions) {
            collect(child)
        }
        val distinctSorted = flattened.distinct().sorted()
        return QueryExpression.All(distinctSorted)
    }

    private fun normalizeAny(any: QueryExpression.Any): QueryExpression.Any {
        val flattened = mutableListOf<QueryExpression>()
        fun collect(expr: QueryExpression) {
            val normalized = normalize(expr)
            if (normalized is QueryExpression.Any) {
                for (child in normalized.expressions) {
                    collect(child)
                }
            } else {
                flattened.add(normalized)
            }
        }
        for (child in any.expressions) {
            collect(child)
        }
        val distinctSorted = flattened.distinct().sorted()
        return QueryExpression.Any(distinctSorted)
    }

    fun normalizedKey(expression: QueryExpression): String {
        return normalize(expression).toCanonicalString()
    }
}
