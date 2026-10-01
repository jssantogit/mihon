package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ProductionDiagnosticWiringTest {
    // Final full-stack CI checkpoint for Diagnostics v2 Waves 1-3.
    @Test
    fun `critical diagnostic and artwork dependencies cannot silently default in production`() {
        var root: File? = File(System.getProperty("user.dir")).absoluteFile
        while (
            root != null &&
            !(File(root, "app/src/main/java").isDirectory && File(root, "domain/src/main/java").isDirectory)
        ) {
            root = root.parentFile
        }
        val repositoryRoot = requireNotNull(root) { "Repository root not found" }

        val forbidden = listOf(
            "private val titleArtworkRepository: TitleArtworkRepository? = null",
            "private val resolveCanonicalArtwork: ResolveCanonicalArtwork? = null",
            "private val resolveCanonicalSourceManga: ResolveCanonicalSourceManga? = null",
            "private val resolveCanonicalMetadata: ResolveCanonicalMetadata? = null",
            "private val contentBindingRepository: ContentBindingRepository? = null",
            "private val diagnosticRecorder: StructuredDiagnosticRecorder = NoOpStructuredDiagnosticRecorder",
        )
        val criticalFiles = listOf(
            "app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/detail/CanonicalTitleScreenModel.kt",
            "app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/home/TsuzukiHomeScreenModel.kt",
            "domain/src/main/java/tachiyomi/domain/tsuzuki/artwork/ResolveCanonicalArtwork.kt",
            "domain/src/main/java/tachiyomi/domain/tsuzuki/integration/interactor/ResolveCanonicalMetadata.kt",
            "domain/src/main/java/tachiyomi/domain/tsuzuki/source/interactor/ResolveCanonicalSourceManga.kt",
            "domain/src/main/java/tachiyomi/domain/tsuzuki/source/interactor/ResolveReadingSource.kt",
        )

        val violations = criticalFiles.flatMap { relativePath ->
            val file = File(repositoryRoot, relativePath)
            val text = file.readText()
            forbidden
                .filter(text::contains)
                .map { pattern -> "$relativePath: $pattern" }
        }

        assertTrue(
            violations.isEmpty(),
            "Production DI must fail closed instead of silently disabling diagnostics/artwork:\n" +
                violations.joinToString("\n"),
        )
    }
}
