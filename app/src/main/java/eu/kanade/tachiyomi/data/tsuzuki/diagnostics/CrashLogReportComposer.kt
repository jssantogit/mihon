package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

/** Composes the existing single-file export while keeping structured history independent of Logcat. */
object CrashLogReportComposer {
    fun compose(
        debugInfo: String,
        extensionsInfo: String?,
        exception: String?,
        structuredHistory: String,
        logcat: LogcatCapture,
    ): String = buildString {
        appendSection(debugInfo)
        extensionsInfo?.takeIf(String::isNotBlank)?.let(::appendSection)
        exception?.takeIf(String::isNotBlank)?.let(::appendSection)
        appendLine("Structured diagnostic history:")
        appendLine(structuredHistory.ifBlank { "(no recent structured events)" })
        appendLine()
        appendLine("Logcat:")
        if (logcat.failures.isNotEmpty()) {
            val markers = logcat.failures.joinToString(", ") { it.marker }
            appendLine("Logcat collection partial or unavailable: $markers")
        }
        if (logcat.content.isNotBlank()) {
            appendLine(logcat.content.trimEnd())
        } else if (logcat.failure == null) {
            appendLine("(empty)")
        }
    }.trimEnd()

    private fun StringBuilder.appendSection(section: String) {
        appendLine(section.trimEnd())
        appendLine()
    }
}
