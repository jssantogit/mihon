package eu.kanade.tachiyomi.network

/** Rewrites one OkHttp HEADERS log line to a privacy-safe line, or drops it. */
internal fun safeHttpLoggingMessage(message: String): String? {
    if (message.startsWith("--> HTTP FAILED") || message.startsWith("<-- HTTP FAILED")) {
        return "HTTP FAILED (details suppressed)"
    }

    REQUEST_START.matchEntire(message)?.let { match ->
        return "--> ${match.groupValues[1]} (URL suppressed)"
    }

    RESPONSE_START.matchEntire(message)?.let { match ->
        val status = match.groupValues[1]
        if (status.toInt() !in 100..599) return null
        val duration = RESPONSE_DURATION.find(message)?.groupValues?.get(1)?.toLongOrNull()
            ?.takeIf { it in 0..MAX_HTTP_DURATION_MILLIS }
        return if (duration == null) "<-- $status" else "<-- $status (${duration}ms)"
    }

    HEADER_LINE.matchEntire(message)?.let { match ->
        val headerName = allowedHeaderNames[match.groupValues[1].lowercase()] ?: return null
        return "$headerName: [redacted]"
    }

    REQUEST_END.matchEntire(message)?.let { match ->
        val method = match.groupValues[1]
        val bytes = match.groupValues[2].toSafeByteCount()
        return if (bytes == null) "--> END $method" else "--> END $method ($bytes-byte body)"
    }

    RESPONSE_END.matchEntire(message)?.let { match ->
        val bytes = match.groupValues[1].toSafeByteCount()
        return if (bytes == null) "<-- END HTTP" else "<-- END HTTP ($bytes-byte body)"
    }

    return null
}

private fun String.toSafeByteCount(): String? =
    toLongOrNull()?.takeIf { it in 0..MAX_BODY_BYTES }?.toString()

private val REQUEST_START = Regex("^--> (GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE|CONNECT) \\S+ \\S+$")
private val RESPONSE_START = Regex("^<-- (\\d{3})\\b.*$")
private val RESPONSE_DURATION = Regex(" \\((\\d+)ms(?:, (?:\\d+-byte|unknown-length) body)?\\)$")
private val HEADER_LINE = Regex("^([A-Za-z0-9!#$%&'*+.^_`|~-]+):.*$")
private val REQUEST_END = Regex(
    "^--> END (GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE|CONNECT)(?: \\((\\d+)-byte body\\))?$",
)
private val RESPONSE_END = Regex("^<-- END HTTP(?: \\((\\d+)-byte body\\))?$")

/** Only emit a fixed set of header names; every value remains redacted. */
private val allowedHeaderNames = mapOf(
    "accept" to "Accept",
    "accept-encoding" to "Accept-Encoding",
    "accept-language" to "Accept-Language",
    "authorization" to "Authorization",
    "cache-control" to "Cache-Control",
    "content-length" to "Content-Length",
    "content-type" to "Content-Type",
    "cookie" to "Cookie",
    "if-modified-since" to "If-Modified-Since",
    "if-none-match" to "If-None-Match",
    "pragma" to "Pragma",
    "proxy-authorization" to "Proxy-Authorization",
    "set-cookie" to "Set-Cookie",
    "user-agent" to "User-Agent",
    "x-api-key" to "X-Api-Key",
)

private const val MAX_HTTP_DURATION_MILLIS = 24 * 60 * 60 * 1_000L
private const val MAX_BODY_BYTES = Int.MAX_VALUE.toLong()
