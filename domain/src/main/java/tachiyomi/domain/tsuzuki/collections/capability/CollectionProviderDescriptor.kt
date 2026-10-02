package tachiyomi.domain.tsuzuki.collections.capability

import tachiyomi.domain.tsuzuki.collections.model.CollectionSortDirection
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.SortDirectionMode
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

data class CollectionProviderDescriptor(
    val providerId: String,
    val displayName: String,
    val scope: CollectionProviderScope,
    val filters: List<CollectionFilterCapability>,
    val sorts: List<CollectionSortCapability>,
    val paging: CollectionPagingCapability,
    val scanPolicy: ResidualScanPolicy = ResidualScanPolicy.DEFAULT,
    val requestBudget: CollectionRequestBudget? = null,
) {
    init {
        require(providerId.isNotBlank()) { "Collection providerId cannot be blank" }
        require(displayName.isNotBlank()) { "Collection provider displayName cannot be blank" }
        require(filters.map(CollectionFilterCapability::id).distinct().size == filters.size) {
            "Collection provider $providerId declares duplicate filter ids"
        }
        require(sorts.map { it.key.stableId }.distinct().size == sorts.size) {
            "Collection provider $providerId declares duplicate sort keys"
        }

        filters.forEach { filter ->
            val field = filter.field
            if (field is QueryField.Custom) {
                require(field.identifier.startsWith("$providerId.")) {
                    "Provider-specific field '${field.identifier}' must be namespaced to '$providerId.'"
                }
            }
        }

        sorts.forEach { sort ->
            val key = sort.key
            if (key is CollectionSortKey.Provider) {
                require(key.providerId == providerId) {
                    "Provider sort '${key.stableId}' cannot be declared by $providerId"
                }
            }
        }
    }

    fun filter(id: String): CollectionFilterCapability? = filters.firstOrNull { it.id == id }

    fun capabilitiesFor(field: QueryField): List<CollectionFilterCapability> =
        filters.filter { it.field == field }

    fun supports(
        field: QueryField,
        operator: QueryOperator,
        executionMode: FilterExecutionMode? = null,
    ): Boolean = filters.any { capability ->
        capability.field == field &&
            operator in capability.operators &&
            (executionMode == null || executionMode in capability.execution)
    }

    fun sort(key: CollectionSortKey): CollectionSortCapability? =
        sorts.firstOrNull { it.key == key }
}

enum class CollectionProviderScope {
    GLOBAL,
    PERSONAL_SERVER,
}

data class CollectionFilterCapability(
    val id: String,
    val field: QueryField,
    val placement: FilterPlacement,
    val operators: Set<QueryOperator>,
    val execution: Set<FilterExecutionMode>,
    val valueSource: FilterValueSource,
    val multiValueMode: MultiValueMode,
    val requiresAuth: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "Collection filter id cannot be blank" }
        require(operators.isNotEmpty()) { "Collection filter $id must support at least one operator" }
        require(execution.isNotEmpty()) { "Collection filter $id must declare at least one execution mode" }
    }
}

enum class FilterPlacement {
    QUICK,
    ADVANCED,
}

enum class FilterExecutionMode {
    REMOTE_EXACT,
    RESIDUAL_EXACT,
    ACCOUNT_SCOPED,
}

enum class MultiValueMode {
    SINGLE,
    ANY,
    ALL,
    INCLUDE_EXCLUDE,
    PROVIDER_NATIVE,
}

sealed interface FilterValueSource {
    data class Static(
        val values: List<FilterOption>,
    ) : FilterValueSource {
        init {
            require(values.isNotEmpty()) { "Static Collection filter options cannot be empty" }
            require(values.map(FilterOption::id).distinct().size == values.size) {
                "Static Collection filter option ids must be unique"
            }
        }
    }

    data class RemoteLookup(
        val lookupId: String,
    ) : FilterValueSource {
        init {
            require(lookupId.isNotBlank()) { "Collection filter lookupId cannot be blank" }
        }
    }

    data object FreeText : FilterValueSource
    data object IntegerRange : FilterValueSource
    data object DecimalRange : FilterValueSource
    data object DateRange : FilterValueSource
    data object BooleanToggle : FilterValueSource
}

data class FilterOption(
    val id: String,
    val label: String,
    val value: QueryValue,
) {
    init {
        require(id.isNotBlank()) { "Collection filter option id cannot be blank" }
        require(label.isNotBlank()) { "Collection filter option label cannot be blank" }
    }
}

data class CollectionSortCapability(
    val key: CollectionSortKey,
    val label: String,
    val directionMode: SortDirectionMode,
    val defaultDirection: CollectionSortDirection? = null,
    val requiresAuth: Boolean = false,
) {
    init {
        require(label.isNotBlank()) { "Collection sort label cannot be blank" }
        when (directionMode) {
            SortDirectionMode.ASC_DESC -> require(defaultDirection != null) {
                "Bidirectional Collection sort '${key.stableId}' requires a default direction"
            }
            SortDirectionMode.ASC_ONLY -> require(defaultDirection == CollectionSortDirection.ASC) {
                "Ascending-only Collection sort '${key.stableId}' must default to ASC"
            }
            SortDirectionMode.DESC_ONLY -> require(defaultDirection == CollectionSortDirection.DESC) {
                "Descending-only Collection sort '${key.stableId}' must default to DESC"
            }
            SortDirectionMode.FIXED_NATIVE -> require(defaultDirection == null) {
                "Fixed native Collection sort '${key.stableId}' cannot declare a direction"
            }
        }
    }
}

enum class CollectionPagingMode {
    OFFSET,
    PAGE,
}

data class CollectionPagingCapability(
    val mode: CollectionPagingMode,
    val maxPageSize: Int? = null,
    val preferredPageSize: Int? = null,
) {
    init {
        require(maxPageSize == null || maxPageSize > 0) { "Collection maxPageSize must be positive" }
        require(preferredPageSize == null || preferredPageSize > 0) {
            "Collection preferredPageSize must be positive"
        }
        require(
            maxPageSize == null ||
                preferredPageSize == null ||
                preferredPageSize <= maxPageSize,
        ) {
            "Collection preferredPageSize cannot exceed maxPageSize"
        }
    }
}

data class ResidualScanPolicy(
    val maxRawItemsPerLogicalPage: Int,
    val maxRemoteRequestsPerLogicalPage: Int,
    val maxElapsedMillis: Long,
) {
    init {
        require(maxRawItemsPerLogicalPage > 0) { "Residual raw item budget must be positive" }
        require(maxRemoteRequestsPerLogicalPage > 0) { "Residual request budget must be positive" }
        require(maxElapsedMillis > 0) { "Residual elapsed budget must be positive" }
    }

    companion object {
        val DEFAULT: ResidualScanPolicy = ResidualScanPolicy(
            maxRawItemsPerLogicalPage = 500,
            maxRemoteRequestsPerLogicalPage = 25,
            maxElapsedMillis = 15_000,
        )
    }
}

data class CollectionRequestBudget(
    val maxRequestsPerSecond: Int? = null,
    val maxRequestsPerMinute: Int? = null,
) {
    init {
        require(maxRequestsPerSecond == null || maxRequestsPerSecond > 0) {
            "Collection per-second request budget must be positive"
        }
        require(maxRequestsPerMinute == null || maxRequestsPerMinute > 0) {
            "Collection per-minute request budget must be positive"
        }
        require(maxRequestsPerSecond != null || maxRequestsPerMinute != null) {
            "Collection request budget must define at least one limit"
        }
    }
}
