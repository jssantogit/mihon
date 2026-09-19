package eu.kanade.tachiyomi.data.collections.query

/**
 * Supported comparison and membership operators in the provider-neutral query language.
 */
enum class QueryOperator(val symbol: String) {
    EQUALS("="),
    NOT_EQUALS("!="),
    IN("IN"),
    GREATER_THAN(">"),
    GREATER_OR_EQUAL(">="),
    LESS_THAN("<"),
    LESS_OR_EQUAL("<="),
    BETWEEN("BETWEEN"),
    CONTAINS("CONTAINS"),
}
