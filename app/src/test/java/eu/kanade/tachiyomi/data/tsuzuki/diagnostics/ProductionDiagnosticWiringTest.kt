package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ProductionDiagnosticWiringTest {
    @Test
    fun `critical diagnostic and artwork dependencies cannot silently default in production`() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java").isDirectory && File(it, "domain/src/main/java").isDirectory }
            ?: error("Repository root not found")

        val forbidden = listOf(
            "private val titleArtworkRepository: TitleArtworkRepository? = null",
            "private val resolveCanonicalArtwork: ResolveCanonicalArtwork? = null",
            "private val resolveCanonicalSourceManga: ResolveCanonicalSourceManga? = null",
            "private val resolveCanonicalMetadata: ResolveCanonicalMetadata? = null",
            "private val contentBindingRepository: ContentBindingRepository? = null",
            "private val diagnosticRecorder: StructuredDiagnosticRecorder = NoOpStructuredDiagnosticRecorder",
        )

        val productionFiles = sequenceOf(
            File(root, "app/src/main/java"),
            File(root, "domain/src/main/java"),
        ).flatMap { directory ->
            directory.walkTopDown().filter { it.isFile && it.extension == "kt" }
        }

        val violations = productionFiles.flatMap { file ->
            val text = file.readText()
            forbidden.asSequence()
                .filter(text::contains)
                .map { pattern -> "${file.relativeTo(root).path}: $pattern" }
        }.toList()

        assertTrue(
            violations.isEmpty(),
            "Production DI must fail closed instead of silently disabling diagnostics/artwork:\n" +
                violations.joinToString("\n"),
        )
    }
}
