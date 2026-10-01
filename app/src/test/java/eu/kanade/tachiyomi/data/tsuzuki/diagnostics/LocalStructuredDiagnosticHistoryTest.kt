package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import android.content.Context
import eu.kanade.domain.base.BasePreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticSanitizer
import java.nio.file.Path

class LocalStructuredDiagnosticHistoryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `does not persist history in incognito mode and resumes persistence when disabled`() {
        val context = mockk<Context> {
            every { noBackupFilesDir } returns temporaryDirectory.toFile()
        }
        val preferences = mockk<BasePreferences>()
        val incognitoMode = mockk<Preference<Boolean>>()
        var incognito = true
        every { preferences.incognitoMode } returns incognitoMode
        every { incognitoMode.get() } answers { incognito }
        val history = LocalStructuredDiagnosticHistory(context, preferences)
        assertFalse(history.persistenceAllowed())
        val event = checkNotNull(
            StructuredDiagnosticSanitizer.sanitize(
                StructuredDiagnosticEvent(
                    timestampMillis = 1234,
                    severity = DiagnosticSeverity.WARN,
                    subsystem = DiagnosticSubsystem.SOURCE,
                    name = DiagnosticEventName.SOURCE_RESOLVE_COMPLETED,
                    sessionId = "00000000-0000-0000-0000-000000000001",
                    operationId = null,
                    stage = DiagnosticStage.COMPLETE,
                    outcome = DiagnosticOutcome.NOT_FOUND_NO_CANDIDATES,
                ),
            ),
        )

        history.submit(event)
        history.flush()
        assertFalse(temporaryDirectory.resolve("tsuzuki/diagnostics").toFile().exists())

        incognito = false
        assertTrue(history.persistenceAllowed())
        history.submit(event)
        assertTrue(history.flush())
        assertTrue(temporaryDirectory.resolve("tsuzuki/diagnostics").toFile().exists())
    }
}
