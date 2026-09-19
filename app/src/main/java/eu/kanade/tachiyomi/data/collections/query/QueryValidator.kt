package eu.kanade.tachiyomi.data.collections.query

/**
 * Validates query expressions against strict semantic and structural constraints.
 */
object QueryValidator {

    fun validatePredicate(field: QueryField, operator: QueryOperator, value: QueryValue) {
        val errors = mutableListOf<String>()
        checkPredicate(field, operator, value, errors)
        if (errors.isNotEmpty()) {
            throw QueryValidationException(
                message = "Invalid predicate for field '${field.identifier}': ${errors.joinToString("; ")}",
                errors = errors,
            )
        }
    }

    private fun checkPredicate(
        field: QueryField,
        operator: QueryOperator,
        value: QueryValue,
        errors: MutableList<String>,
    ) {
        when (operator) {
            QueryOperator.BETWEEN -> {
                if (value !is QueryValue.RangeValue) {
                    errors.add("BETWEEN operator requires a RangeValue, but got ${value::class.simpleName}")
                } else {
                    val lower = value.lower
                    val upper = value.upper
                    val isNumeric = (lower is QueryValue.IntegerValue || lower is QueryValue.DoubleValue) &&
                        (upper is QueryValue.IntegerValue || upper is QueryValue.DoubleValue)
                    val isString = lower is QueryValue.StringValue && upper is QueryValue.StringValue
                    val isTemporal = lower is QueryValue.RelativeTemporal && upper is QueryValue.RelativeTemporal

                    if (!isNumeric && !isString && !isTemporal) {
                        errors.add(
                            "BETWEEN bounds must be of compatible types (numeric, string, or temporal), but got lower=${lower::class.simpleName} and upper=${upper::class.simpleName}",
                        )
                    } else if (lower > upper) {
                        errors.add(
                            "BETWEEN lower bound cannot exceed upper bound: lower=${lower.canonicalString()}, upper=${upper.canonicalString()}",
                        )
                    }
                }
            }
            QueryOperator.IN -> {
                if (value !is QueryValue.ListValue) {
                    errors.add("IN operator requires a ListValue, but got ${value::class.simpleName}")
                } else {
                    if (value.values.isEmpty()) {
                        errors.add("IN operator requires a non-empty list of values")
                    }
                    if (value.values.any { it is QueryValue.ListValue || it is QueryValue.RangeValue }) {
                        errors.add("IN operator does not allow nested ListValue or RangeValue elements")
                    }
                }
            }
            QueryOperator.GREATER_THAN,
            QueryOperator.GREATER_OR_EQUAL,
            QueryOperator.LESS_THAN,
            QueryOperator.LESS_OR_EQUAL,
            -> {
                val isNumeric = value is QueryValue.IntegerValue || value is QueryValue.DoubleValue
                val isTemporal = value is QueryValue.RelativeTemporal
                if (!isNumeric && !isTemporal) {
                    errors.add(
                        "Numeric comparison operator ${operator.name} requires a numeric or temporal value, but got ${value::class.simpleName}",
                    )
                }
            }
            QueryOperator.CONTAINS -> {
                if (value is QueryValue.RangeValue || value is QueryValue.ListValue ||
                    value is QueryValue.BooleanValue
                ) {
                    errors.add(
                        "CONTAINS operator requires a string or scalar value, but got ${value::class.simpleName}",
                    )
                }
            }
            QueryOperator.EQUALS,
            QueryOperator.NOT_EQUALS,
            -> {
                if (value is QueryValue.RangeValue || value is QueryValue.ListValue) {
                    errors.add("${operator.name} operator requires a scalar value, but got ${value::class.simpleName}")
                }
            }
        }
    }

    fun validate(expression: QueryExpression): QueryValidationResult {
        val errors = mutableListOf<String>()
        collectValidationErrors(expression, errors)
        return if (errors.isEmpty()) {
            QueryValidationResult.Valid
        } else {
            QueryValidationResult.Invalid(errors)
        }
    }

    fun validateOrThrow(expression: QueryExpression) {
        val result = validate(expression)
        if (result is QueryValidationResult.Invalid) {
            result.throwException()
        }
    }

    private fun collectValidationErrors(expression: QueryExpression, errors: MutableList<String>) {
        when (expression) {
            is QueryExpression.Predicate -> {
                checkPredicate(expression.field, expression.operator, expression.value, errors)
            }
            is QueryExpression.All -> {
                if (expression.expressions.isEmpty()) {
                    errors.add("ALL expression cannot be empty")
                }
                for (child in expression.expressions) {
                    collectValidationErrors(child, errors)
                }
            }
            is QueryExpression.Any -> {
                if (expression.expressions.isEmpty()) {
                    errors.add("ANY expression cannot be empty")
                }
                for (child in expression.expressions) {
                    collectValidationErrors(child, errors)
                }
            }
            is QueryExpression.Not -> {
                collectValidationErrors(expression.expression, errors)
            }
        }
    }
}
