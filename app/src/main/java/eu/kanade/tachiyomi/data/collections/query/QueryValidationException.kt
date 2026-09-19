package eu.kanade.tachiyomi.data.collections.query

/**
 * Exception thrown when a query expression violates structural or semantic constraints.
 */
class QueryValidationException(
    message: String,
    val errors: List<String> = listOf(message),
) : IllegalArgumentException(message)

/**
 * Result taxonomy for non-throwing validation passes.
 */
sealed interface QueryValidationResult {
    data object Valid : QueryValidationResult
    data class Invalid(val errors: List<String>) : QueryValidationResult {
        fun throwException(): Nothing = throw QueryValidationException(
            message = "Query validation failed: ${errors.joinToString("; ")}",
            errors = errors,
        )
    }
}
