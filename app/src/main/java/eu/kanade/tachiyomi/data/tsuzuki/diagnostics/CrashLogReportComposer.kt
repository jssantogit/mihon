package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

/** Composes the existing single-file export while keeping structured history independent of Logcat. */
object CrashLogReportComposer {
    fun compose(
        debugInfo: String,
        extensionsInfo: String?,
        exception: String?,
        diagnosticSummary: String,
        structuredHistory: String,
        logcat: LogcatCapture,
        runtimeSnapshot: String? = null,
        captureWindow: String? = null,
        crashContext: String? = null,
    ): String = buildString {
        appendSection(debugInfo)
        runtimeSnapshot?.takeIf(String::isNotBlank)?.let {
            appendLine("Diagnostic runtime snapshot:")
            appendSection(it)
        }
        captureWindow?.takeIf(String::isNotBlank)?.let {
            appendLine("Diagnostic capture window:")
            appendSection(it)
        }
        crashContext?.takeIf(String::isNotBlank)?.let {
            appendLine("Diagnostic crash context:")
            appendSection(it)
        }
        extensionsInfo?.takeIf(String::isNotBlank)?.let { appendSection(it) }
        exception?.takeIf(String::isNotBlank)?.let { appendSection(it) }
        appendLine("Diagnostic summary:")
        appendLine(diagnosticSummary.ifBlank { "(unavailable)" })
        appendLine()
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
