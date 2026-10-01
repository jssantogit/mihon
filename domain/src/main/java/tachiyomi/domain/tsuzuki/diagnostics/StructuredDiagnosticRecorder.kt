package tachiyomi.domain.tsuzuki.diagnostics

/** Best-effort entry point for recording sanitized Tsuzuki operation diagnostics. */
interface StructuredDiagnosticRecorder {
    /** Stable UUID for the current process session. */
    val sessionId: String

    /** True only while the user-controlled bounded detailed capture window is active. */
    val detailedCaptureActive: Boolean
        get() = false

    /** Returns a session-scoped pseudonymous reference, or null for an empty identity. */
    fun canonicalTitleReference(canonicalTitleId: String): String?

    /** Returns a session-scoped pseudonymous reference, or null for an invalid Mihon id. */
    fun mihonMangaReference(mihonMangaId: Long): String?

    /** Must be non-suspending and best-effort; diagnostic failures must not affect the caller. */
    fun record(event: StructuredDiagnosticEvent)
}

/** Default for domain callers when the app recording service is not wired. */
object NoOpStructuredDiagnosticRecorder : StructuredDiagnosticRecorder {
    override val sessionId: String = "00000000-0000-0000-0000-000000000000"

    override fun canonicalTitleReference(canonicalTitleId: String): String? = null

    override fun mihonMangaReference(mihonMangaId: Long): String? = null

    override fun record(event: StructuredDiagnosticEvent) = Unit
}
