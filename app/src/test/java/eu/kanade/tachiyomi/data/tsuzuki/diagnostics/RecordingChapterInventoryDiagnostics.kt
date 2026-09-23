package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics

internal class RecordingChapterInventoryDiagnostics : ChapterInventoryDiagnostics {
    val events = mutableListOf<ChapterInventoryDiagnosticEvent>()
    private var recordingTitleId: String? = null

    override fun start(canonicalTitleId: String): String {
        recordingTitleId = canonicalTitleId
        return "test-session"
    }

    override fun stop() {
        recordingTitleId = null
    }

    override fun clear() {
        events.clear()
        recordingTitleId = null
    }

    override fun isRecording(canonicalTitleId: String): Boolean = recordingTitleId == canonicalTitleId

    override fun record(event: ChapterInventoryDiagnosticEvent) {
        if (recordingTitleId != null) events += event
    }

    override fun report(): String = events.joinToString(separator = "\n")
}
