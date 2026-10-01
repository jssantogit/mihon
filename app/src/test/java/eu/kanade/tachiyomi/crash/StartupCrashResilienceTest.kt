package eu.kanade.tachiyomi.crash

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class StartupCrashResilienceTest {

    @Test
    fun `crash handler is installed before application graph injection`() {
        val root = repositoryRoot()
        val app = File(root, "app/src/main/java/eu/kanade/tachiyomi/App.kt").readText()

        val handlerIndex = app.indexOf("GlobalExceptionHandler.initialize")
        val graphIndex = app.indexOf("graph.inject(this)")

        assertTrue(handlerIndex >= 0)
        assertTrue(graphIndex >= 0)
        assertTrue(handlerIndex < graphIndex)
        assertTrue(app.contains("override fun attachBaseContext(base: Context)"))
        assertTrue(app.contains("process.endsWith(ERROR_HANDLER_PROCESS_SUFFIX)"))
    }

    @Test
    fun `crash activity does not depend on app graph or BaseActivity`() {
        val root = repositoryRoot()
        val crashActivity = File(
            root,
            "app/src/main/java/eu/kanade/tachiyomi/crash/CrashActivity.kt",
        ).readText()

        assertTrue(crashActivity.contains("class CrashActivity : ComponentActivity()"))
        assertFalse(crashActivity.contains("BaseActivity"))
        assertFalse(crashActivity.contains("appGraph"))
        assertFalse(crashActivity.contains("setComposeContent"))
    }

    private fun repositoryRoot(): File {
        var root: File? = File(System.getProperty("user.dir")).absoluteFile
        while (
            root != null &&
            !(File(root, "app/src/main/java").isDirectory && File(root, "domain/src/main/java").isDirectory)
        ) {
            root = root.parentFile
        }
        return requireNotNull(root) { "Repository root not found" }
    }
}
