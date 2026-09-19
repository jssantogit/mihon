package tachiyomi.domain.tsuzuki.collections.execution

import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

object ResidualEvaluator {

    fun support(expression: QueryExpression?): ResidualSupport {
        if (expression == null) return ResidualSupport.Supported

        val reasons = mutableListOf<String>()
        collectUnsupported(expression, reasons)
        return if (reasons.isEmpty()) {
            ResidualSupport.Supported
        } else {
            ResidualSupport.Unsupported(reasons.distinct())
        }
    }

    fun evaluate(expression: QueryExpression?, item: CatalogItem): ResidualEvaluation {
        if (expression == null) {
            return ResidualEvaluation.Evaluated(TruthValue.TRUE)
        }

        when (val support = support(expression)) {
            ResidualSupport.Supported -> Unit
            is ResidualSupport.Unsupported -> return ResidualEvaluation.Unsupported(support.reasons)
        }

        return ResidualEvaluation.Evaluated(evaluateSupported(expression, item))
    }

    private fun collectUnsupported(expression: QueryExpression, reasons: MutableList<String>) {
        when (expression) {
            is QueryExpression.Predicate -> predicateSupportReason(expression)?.let(reasons::add)
            is QueryExpression.Not -> collectUnsupported(expression.expression, reasons)
            is QueryExpression.All -> expression.expressions.forEach { collectUnsupported(it, reasons) }
            is QueryExpression.Any -> expression.expressions.forEach { collectUnsupported(it, reasons) }
        }
    }

    private fun predicateSupportReason(predicate: QueryExpression.Predicate): String? {
        val operator = predicate.operator
        val value = predicate.value

        return when (predicate.field) {
            QueryField.Standard.WORK_TYPE,
            QueryField.Standard.STATUS,
            -> if (
                operator in setOf(QueryOperator.EQUALS, QueryOperator.NOT_EQUALS, QueryOperator.IN) &&
                isStringOperand(operator, value)
            ) {
                null
            } else {
                "Unsupported operator/value for ${predicate.field.identifier}: ${operator.name}"
            }

            QueryField.Standard.CHAPTER_COUNT,
            QueryField.Standard.VOLUME_COUNT,
            QueryField.Standard.SCORE,
            QueryField.Standard.RATING,
            -> if (
                operator in setOf(
                    QueryOperator.EQUALS,
                    QueryOperator.NOT_EQUALS,
                    QueryOperator.IN,
                    QueryOperator.GREATER_THAN,
                    QueryOperator.GREATER_OR_EQUAL,
                    QueryOperator.LESS_THAN,
                    QueryOperator.LESS_OR_EQUAL,
                    QueryOperator.BETWEEN,
                ) &&
                isNumericOperand(operator, value)
            ) {
                null
            } else {
                "Unsupported operator/value for ${predicate.field.identifier}: ${operator.name}"
            }

            QueryField.Standard.GENRE,
            QueryField.Standard.TAG,
            -> if (
                operator in setOf(
                    QueryOperator.EQUALS,
                    QueryOperator.NOT_EQUALS,
                    QueryOperator.IN,
                    QueryOperator.CONTAINS,
                ) &&
                isStringOperand(operator, value)
            ) {
                null
            } else {
                "Unsupported operator/value for ${predicate.field.identifier}: ${operator.name}"
            }

            else -> "Residual field is not currently evaluable from CatalogItem: ${predicate.field.identifier}"
        }
    }

    private fun isStringOperand(operator: QueryOperator, value: QueryValue): Boolean = when (operator) {
        QueryOperator.IN ->
            value is QueryValue.ListValue &&
                value.values.isNotEmpty() &&
                value.values.all { it is QueryValue.StringValue }
        else -> value is QueryValue.StringValue
    }

    private fun isNumericOperand(operator: QueryOperator, value: QueryValue): Boolean = when (operator) {
        QueryOperator.IN ->
            value is QueryValue.ListValue &&
                value.values.isNotEmpty() &&
                value.values.all(::isNumeric)
        QueryOperator.BETWEEN ->
            value is QueryValue.RangeValue &&
                isNumeric(value.lower) &&
                isNumeric(value.upper)
        else -> isNumeric(value)
    }

    private fun isNumeric(value: QueryValue): Boolean =
        value is QueryValue.IntegerValue || value is QueryValue.DoubleValue

    private fun evaluateSupported(expression: QueryExpression, item: CatalogItem): TruthValue = when (expression) {
        is QueryExpression.Predicate -> evaluatePredicate(expression, item)
        is QueryExpression.Not -> evaluateSupported(expression.expression, item).not()
        is QueryExpression.All -> TruthValue.all(expression.expressions.map { evaluateSupported(it, item) })
        is QueryExpression.Any -> TruthValue.any(expression.expressions.map { evaluateSupported(it, item) })
    }

    private fun evaluatePredicate(predicate: QueryExpression.Predicate, item: CatalogItem): TruthValue {
        return when (val fieldValue = resolveField(predicate.field, item)) {
            FieldValue.Missing -> TruthValue.UNKNOWN
            is FieldValue.Scalar -> evaluateScalar(fieldValue.value, predicate.operator, predicate.value)
            is FieldValue.Collection -> evaluateCollection(fieldValue.values, predicate.operator, predicate.value)
        }
    }

    private fun resolveField(field: QueryField, item: CatalogItem): FieldValue = when (field) {
        QueryField.Standard.WORK_TYPE -> when (item.format) {
            CatalogItemFormat.UNKNOWN -> FieldValue.Missing
            else -> FieldValue.Scalar(QueryValue.StringValue(item.format.name))
        }

        QueryField.Standard.STATUS -> when (item.status) {
            CatalogItemStatus.UNKNOWN -> FieldValue.Missing
            else -> FieldValue.Scalar(QueryValue.StringValue(item.status.name))
        }

        QueryField.Standard.CHAPTER_COUNT ->
            item.chapterCount
                ?.let { FieldValue.Scalar(QueryValue.IntegerValue(it)) }
                ?: FieldValue.Missing

        QueryField.Standard.VOLUME_COUNT ->
            item.volumeCount
                ?.let { FieldValue.Scalar(QueryValue.IntegerValue(it)) }
                ?: FieldValue.Missing

        QueryField.Standard.SCORE,
        QueryField.Standard.RATING,
        ->
            item.score
                ?.let { FieldValue.Scalar(QueryValue.DoubleValue(it.value)) }
                ?: FieldValue.Missing

        QueryField.Standard.GENRE -> if (item.genres.isEmpty()) {
            FieldValue.Missing
        } else {
            FieldValue.Collection(item.genres.map { QueryValue.StringValue(it) })
        }

        QueryField.Standard.TAG -> if (item.tags.isEmpty()) {
            FieldValue.Missing
        } else {
            FieldValue.Collection(item.tags.map { QueryValue.StringValue(it) })
        }

        else -> error("Unsupported field reached evaluator after preflight: ${field.identifier}")
    }

    private fun evaluateScalar(actual: QueryValue, operator: QueryOperator, expected: QueryValue): TruthValue {
        return when (operator) {
            QueryOperator.EQUALS -> truth(scalarEquals(actual, expected))
            QueryOperator.NOT_EQUALS -> truth(!scalarEquals(actual, expected))
            QueryOperator.IN -> {
                val values = (expected as QueryValue.ListValue).values
                truth(values.any { scalarEquals(actual, it) })
            }
            QueryOperator.GREATER_THAN -> compareNumeric(actual, expected) { it > 0 }
            QueryOperator.GREATER_OR_EQUAL -> compareNumeric(actual, expected) { it >= 0 }
            QueryOperator.LESS_THAN -> compareNumeric(actual, expected) { it < 0 }
            QueryOperator.LESS_OR_EQUAL -> compareNumeric(actual, expected) { it <= 0 }
            QueryOperator.BETWEEN -> {
                val range = expected as QueryValue.RangeValue
                val lower = numericValue(range.lower)
                val upper = numericValue(range.upper)
                val actualNumber = numericValue(actual)
                truth(actualNumber >= lower && actualNumber <= upper)
            }
            QueryOperator.CONTAINS -> {
                val actualString = (actual as QueryValue.StringValue).value
                val expectedString = (expected as QueryValue.StringValue).value
                truth(actualString.contains(expectedString, ignoreCase = true))
            }
        }
    }

    private fun evaluateCollection(
        actual: List<QueryValue.StringValue>,
        operator: QueryOperator,
        expected: QueryValue,
    ): TruthValue {
        return when (operator) {
            QueryOperator.EQUALS,
            QueryOperator.CONTAINS,
            -> {
                val expectedString = expected as QueryValue.StringValue
                truth(actual.any { stringEquals(it.value, expectedString.value) })
            }
            QueryOperator.NOT_EQUALS -> {
                val expectedString = expected as QueryValue.StringValue
                truth(actual.none { stringEquals(it.value, expectedString.value) })
            }
            QueryOperator.IN -> {
                val expectedValues = (expected as QueryValue.ListValue).values
                    .map { it as QueryValue.StringValue }
                truth(
                    actual.any { actualValue ->
                        expectedValues.any { expectedValue -> stringEquals(actualValue.value, expectedValue.value) }
                    },
                )
            }
            else -> error("Unsupported collection operator reached evaluator: $operator")
        }
    }

    private fun scalarEquals(left: QueryValue, right: QueryValue): Boolean = when {
        isNumeric(left) && isNumeric(right) -> numericValue(left) == numericValue(right)
        left is QueryValue.StringValue && right is QueryValue.StringValue -> stringEquals(left.value, right.value)
        left is QueryValue.BooleanValue && right is QueryValue.BooleanValue -> left.value == right.value
        else -> false
    }

    private fun stringEquals(left: String, right: String): Boolean = left.equals(right, ignoreCase = true)

    private fun compareNumeric(
        actual: QueryValue,
        expected: QueryValue,
        predicate: (Int) -> Boolean,
    ): TruthValue {
        return truth(predicate(numericValue(actual).compareTo(numericValue(expected))))
    }

    private fun numericValue(value: QueryValue): Double = when (value) {
        is QueryValue.IntegerValue -> value.value.toDouble()
        is QueryValue.DoubleValue -> value.value
        else -> error("Expected numeric query value but found ${value::class.simpleName}")
    }

    private fun truth(value: Boolean): TruthValue = if (value) TruthValue.TRUE else TruthValue.FALSE

    private sealed interface FieldValue {
        data object Missing : FieldValue

        data class Scalar(
            val value: QueryValue,
        ) : FieldValue

        data class Collection(
            val values: List<QueryValue.StringValue>,
        ) : FieldValue
    }
}
