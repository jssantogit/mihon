package eu.kanade.tachiyomi.data.tsuzuki

import eu.kanade.tachiyomi.network.HttpException

/** Extract only a numeric HTTP status from a known Mihon exception type. */
internal fun Throwable.diagnosticHttpStatus(): Int? =
    generateSequence(this) { it.cause }
        .take(5)
        .filterIsInstance<HttpException>()
        .map { it.code }
        .firstOrNull { it in 100..599 }
