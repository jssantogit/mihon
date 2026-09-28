package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Real APK loading only on a clean, isolated Android emulator. This test never contacts MangaFire.
 * The live search is a separate, explicitly opted-in method and is never a Fast CI gate.
 */
@RunWith(AndroidJUnit4::class)
class MangaFireFixtureInstrumentedTest {

    @Test
    fun loadsRealExtensionAndRegistersInternalSources() {
        runBlocking {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as App
            val extension = loadTrustedFixture(app)
            assertEquals("1.6.34", extension.versionName)
            assertEquals(
                setOf("en", "es", "es-419", "fr", "ja", "pt", "pt-BR"),
                extension.sources.map { it.lang }.toSet(),
            )

            val englishSource = extension.sources.single { it.lang == "en" }
            assertEquals(6084907896154116083L, englishSource.id)
            val registered = withTimeout(30_000L) {
                app.graph.sourceManager.sources.first { sources ->
                    extension.sources.all { installed ->
                        sources.any { it.id == installed.id }
                    }
                }
            }
            assertTrue(registered.any { it.id == englishSource.id })
            Log.i(LOG_TAG, "MANGAFIRE_FIXTURE|load=SUCCESS|registered=" + extension.sources.size)
        }
    }

    @Test
    fun optionalLiveEnglishSearch() {
        runBlocking {
            assumeTrue(
                "Live provider probe must be explicitly enabled in a manually dispatched workflow",
                InstrumentationRegistry.getArguments().getString("allowLiveProvider") == "true",
            )
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as App
            val extension = loadTrustedFixture(app)
            val source = extension.sources.single { it.lang == "en" } as CatalogueSource
            val startedAt = SystemClock.elapsedRealtime()

            val result = try {
                withTimeout(40_000L) {
                    withContext(Dispatchers.IO) {
                        source.getSearchManga(1, "One Punch Man", source.getFilterList())
                    }
                }
            } catch (cancel: CancellationException) {
                if (cancel !is TimeoutCancellationException) throw cancel
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                fail("MANGAFIRE_LIVE|outcome=TIMEOUT|elapsedMs=" + elapsed)
                return@runBlocking
            } catch (error: Throwable) {
                val causes = generateSequence(error) { it.cause }.take(5).toList()
                val status = causes.filterIsInstance<HttpException>()
                    .map(HttpException::code).firstOrNull { it in 100..599 }
                val explicitCaptcha = causes.any {
                    it.message.orEmpty().contains("captcha_required", ignoreCase = true) ||
                        it.message.orEmpty().contains("shape-selecting captcha", ignoreCase = true)
                }
                val kind = when {
                    explicitCaptcha -> "CAPTCHA_REQUIRED"
                    causes.any { it is SocketTimeoutException } -> "TIMEOUT"
                    status != null -> "HTTP_RESPONSE"
                    causes.any { it is ConnectException || it is UnknownHostException } -> "NETWORK_FAILURE"
                    else -> "INDETERMINATE"
                }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                // Never attach the throwable or print response bodies, raw URLs, headers, or cookies.
                fail(
                    "MANGAFIRE_LIVE|outcome=" + kind + "|httpStatus=" + (status ?: "unknown") +
                        "|elapsedMs=" + elapsed,
                )
                return@runBlocking
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            Log.i(LOG_TAG, "MANGAFIRE_LIVE|outcome=SUCCESS|received=" + result.mangas.size + "|elapsedMs=" + elapsed)
            assertTrue("MANGAFIRE_LIVE|outcome=EMPTY|elapsedMs=" + elapsed, result.mangas.isNotEmpty())
        }
    }

    private suspend fun loadTrustedFixture(app: App): Extension.Installed {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(
            PACKAGE_NAME,
            PackageManager.GET_META_DATA,
        )
        assertEquals("1.6.34", info.versionName)

        val manager = app.graph.extensionManager
        manager.getInstalledExtensions().firstOrNull { it.pkgName == PACKAGE_NAME }?.let {
            return it
        }

        val untrusted = withTimeout(45_000L) {
            manager.untrustedExtensionsFlow.first { extensions ->
                extensions.any { it.pkgName == PACKAGE_NAME }
            }.single { it.pkgName == PACKAGE_NAME }
        }
        assertEquals("1.6.34", untrusted.versionName)
        // The fixture was SHA-256 verified before adb installation, and this trust remains
        // confined to the disposable emulator's test app data.
        app.graph.sourcePreferences.showNsfwSource.set(true)
        manager.trust(untrusted)
        return withTimeout(30_000L) {
            manager.installedExtensionsFlow.first { extensions ->
                extensions.any { it.pkgName == PACKAGE_NAME }
            }.single { it.pkgName == PACKAGE_NAME }
        }
    }

    private companion object {
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.all.mangafire"
        const val LOG_TAG = "TsuzukiMangaFire"
    }
}
