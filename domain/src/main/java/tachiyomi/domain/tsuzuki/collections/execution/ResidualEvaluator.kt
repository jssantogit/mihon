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
            QueryField.Standard.COUNTRY,
            -> if (
                operator in setOf(QueryOperator.EQUALS, QueryOperator.NOT_EQUALS, QueryOperator.IN) &&
                isStringOperand(operator, value)
            ) {
                null
            } else {
                "Unsupported operator/value for ${predicate.field.identifier}: ${operator.name}"
            }

            QueryField.Standard.START_YEAR,
            QueryField.Standard.RELEASE_YEAR,
            QueryField.Standard.CHAPTER_COUNT,
            QueryField.Standard.VOLUME_COUNT,
            QueryField.Standard.SCORE,
            QueryField.Standard.RATING,
            QueryField.Standard.POPULARITY,
            QueryField.Standard.FAVORITES,
            QueryField.Standard.RANK,
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
            QueryField.Standard.AUTHOR,
            QueryField.Standard.ARTIST,
            QueryField.Standard.PUBLISHER,
            QueryField.Standard.MAGAZINE,
            QueryField.Standard.CATEGORY,
            QueryField.Standard.DEMOGRAPHIC,
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

            QueryField.Standard.START_DATE,
            QueryField.Standard.END_DATE,
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
                isDateOperand(operator, value)
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

    private fun isDateOperand(operator: QueryOperator, value: QueryValue): Boolean = when (operator) {
        QueryOperator.IN ->
            value is QueryValue.ListValue &&
                value.values.isNotEmpty() &&
                value.values.all { it is QueryValue.StringValue }
        QueryOperator.BETWEEN ->
            value is QueryValue.RangeValue &&
                value.lower is QueryValue.StringValue &&
                value.upper is QueryValue.StringValue
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

        QueryField.Standard.START_YEAR,
        QueryField.Standard.RELEASE_YEAR,
        ->
            item.startDate
                ?.extractYear()
                ?.let { FieldValue.Scalar(QueryValue.IntegerValue(it)) }
                ?: FieldValue.Missing

        QueryField.Standard.START_DATE ->
            item.startDate
                ?.let { FieldValue.Scalar(QueryValue.StringValue(it)) }
                ?: FieldValue.Missing

        QueryField.Standard.END_DATE ->
            item.endDate
                ?.let { FieldValue.Scalar(QueryValue.StringValue(it)) }
                ?: FieldValue.Missing

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

        QueryField.Standard.GENRE -> item.genres.asFieldValue()
        QueryField.Standard.TAG -> item.tags.asFieldValue()
        QueryField.Standard.AUTHOR -> item.authors.asFieldValue()
        QueryField.Standard.ARTIST -> item.artists.asFieldValue()
        QueryField.Standard.PUBLISHER -> item.publishers.asFieldValue()
        QueryField.Standard.MAGAZINE -> item.magazines.asFieldValue()
        QueryField.Standard.CATEGORY -> item.categories.asFieldValue()
        QueryField.Standard.DEMOGRAPHIC -> item.demographics.asFieldValue()

        QueryField.Standard.COUNTRY ->
            item.country
                ?.takeIf(String::isNotBlank)
                ?.let { FieldValue.Scalar(QueryValue.StringValue(it)) }
                ?: FieldValue.Missing

        QueryField.Standard.POPULARITY ->
            item.popularity
                ?.let { FieldValue.Scalar(QueryValue.IntegerValue(it)) }
                ?: FieldValue.Missing

        QueryField.Standard.FAVORITES ->
            item.favorites
                ?.let { FieldValue.Scalar(QueryValue.IntegerValue(it)) }
                ?: FieldValue.Missing

        QueryField.Standard.RANK ->
            item.rank
                ?.let { FieldValue.Scalar(QueryValue.DoubleValue(it)) }
                ?: FieldValue.Missing

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
            QueryOperator.GREATER_THAN -> compareOrdered(actual, expected) { it > 0 }
            QueryOperator.GREATER_OR_EQUAL -> compareOrdered(actual, expected) { it >= 0 }
            QueryOperator.LESS_THAN -> compareOrdered(actual, expected) { it < 0 }
            QueryOperator.LESS_OR_EQUAL -> compareOrdered(actual, expected) { it <= 0 }
            QueryOperator.BETWEEN -> evaluateBetween(actual, expected as QueryValue.RangeValue)
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

    private fun compareOrdered(
        actual: QueryValue,
        expected: QueryValue,
        predicate: (Int) -> Boolean,
    ): TruthValue {
        val comparison = when {
            isNumeric(actual) && isNumeric(expected) -> numericValue(actual).compareTo(numericValue(expected))
            actual is QueryValue.StringValue && expected is QueryValue.StringValue ->
                actual.value.compareTo(expected.value)
            else -> error("Expected comparable query values")
        }
        return truth(predicate(comparison))
    }

    private fun evaluateBetween(
        actual: QueryValue,
        range: QueryValue.RangeValue,
    ): TruthValue = when {
        isNumeric(actual) && isNumeric(range.lower) && isNumeric(range.upper) -> {
            val actualNumber = numericValue(actual)
            truth(actualNumber >= numericValue(range.lower) && actualNumber <= numericValue(range.upper))
        }
        actual is QueryValue.StringValue &&
            range.lower is QueryValue.StringValue &&
            range.upper is QueryValue.StringValue -> {
            truth(actual.value >= range.lower.value && actual.value <= range.upper.value)
        }
        else -> error("Expected comparable range query values")
    }

    private fun numericValue(value: QueryValue): Double = when (value) {
        is QueryValue.IntegerValue -> value.value.toDouble()
        is QueryValue.DoubleValue -> value.value
        else -> error("Expected numeric query value but found ${value::class.simpleName}")
    }

    private fun truth(value: Boolean): TruthValue = if (value) TruthValue.TRUE else TruthValue.FALSE

    private fun String.extractYear(): Int? =
        take(4)
            .takeIf { it.length == 4 && it.all(Char::isDigit) }
            ?.toIntOrNull()

    private fun List<String>.asFieldValue(): FieldValue =
        if (isEmpty()) {
            FieldValue.Missing
        } else {
            FieldValue.Collection(map { QueryValue.StringValue(it) })
        }

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
