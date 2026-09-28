package tachiyomi.domain.tsuzuki.source.model

/** Closed cause category captured at the Mihon title-search boundary. */
enum class ReadingSourceFailureKind {
    SOURCE_DISABLED,
    SOURCE_UNAVAILABLE,
    HTTP_RESPONSE,
    NETWORK_FAILURE,
    TIMEOUT,
    CAPTCHA_REQUIRED,
    MALFORMED_RESPONSE,
    EXTENSION_FAILURE,
    INDETERMINATE,
}

/**
 * Sanitized search failure metadata while retaining the original error as [cause].
 *
 * The message contains only the closed category. Never use provider response text, URL, headers,
 * or arbitrary cause text in exported diagnostics.
 */
class ReadingSourceSearchFailure(
    val kind: ReadingSourceFailureKind,
    httpStatus: Int? = null,
    cause: Throwable? = null,
) : IllegalStateException("Reading source search failed (${kind.name})", cause) {
    val httpStatus: Int? = httpStatus?.takeIf { it in 100..599 }
}
