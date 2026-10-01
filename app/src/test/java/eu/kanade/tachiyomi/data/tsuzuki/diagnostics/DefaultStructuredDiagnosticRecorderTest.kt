package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class DefaultStructuredDiagnosticRecorderTest {
    @Test
    fun `uses valid stable per-process session and private type-specific references`() {
        val captureState = mockk<DiagnosticCaptureState>(relaxed = true)
        val crashContextStore = mockk<DiagnosticCrashContextStore>(relaxed = true)
        val recorder = DefaultStructuredDiagnosticRecorder(
            mockk(),
            DiagnosticRecorderHealth(),
            captureState,
            crashContextStore,
        )
        val otherProcess = DefaultStructuredDiagnosticRecorder(
            mockk(),
            DiagnosticRecorderHealth(),
            captureState,
            crashContextStore,
        )

        assertEquals(UUID.fromString(recorder.sessionId).toString(), recorder.sessionId)
        assertNotEquals(recorder.sessionId, otherProcess.sessionId)

        val canonicalRef = recorder.canonicalTitleReference("canonical-title-id")!!
        val mihonRef = recorder.mihonMangaReference(123L)!!
        assertTrue(canonicalRef.matches(Regex("^[0-9a-f]{32}$")))
        assertTrue(mihonRef.matches(Regex("^[0-9a-f]{32}$")))
        assertEquals(canonicalRef, recorder.canonicalTitleReference("canonical-title-id"))
        assertNotEquals(canonicalRef, mihonRef)
        assertNotEquals(canonicalRef, otherProcess.canonicalTitleReference("canonical-title-id"))
        assertNull(recorder.canonicalTitleReference(""))
        assertNull(recorder.mihonMangaReference(0))
    }
}
