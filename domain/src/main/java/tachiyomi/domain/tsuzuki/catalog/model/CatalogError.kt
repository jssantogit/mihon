package tachiyomi.domain.tsuzuki.catalog.model

sealed class CatalogError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NetworkError(cause: Throwable) : CatalogError("Network connectivity failure: ${cause.message}", cause)
    class HttpError(val statusCode: Int, message: String) : CatalogError("HTTP $statusCode: $message")
    class RateLimitExceeded(val retryAfterSeconds: Long? = null) : CatalogError("Provider rate limit exceeded")
    class SerializationError(cause: Throwable) : CatalogError("Serialization error: ${cause.message}", cause)
    class ProviderUnavailable(message: String, cause: Throwable? = null) : CatalogError(message, cause)
    class ItemNotFound(val providerId: String) : CatalogError("Catalog item not found: $providerId")
}
