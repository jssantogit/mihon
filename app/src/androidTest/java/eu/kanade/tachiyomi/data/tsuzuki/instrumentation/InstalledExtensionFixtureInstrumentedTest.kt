package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceSearchFailure

/** Loads an exact installed extension on an isolated emulator; never contacts its site. */
@RunWith(AndroidJUnit4::class)
class InstalledExtensionFixtureInstrumentedTest {

    @Test
    fun loadsRealExtensionAndRegistersSources() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val packageName = requireNotNull(
                InstrumentationRegistry.getArguments().getString("fixturePackageName"),
            )
            val expectedVersion = requireNotNull(
                InstrumentationRegistry.getArguments().getString("fixtureVersionName"),
            )
            assertTrue(packageName.startsWith("eu.kanade.tachiyomi.extension."))
            val app = instrumentation.targetContext.applicationContext as App

            @Suppress("DEPRECATION")
            val installedPackage = app.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_META_DATA,
            )
            assertEquals(expectedVersion, installedPackage.versionName)
            app.graph.sourcePreferences.showNsfwSource.set(true)
            val manager = app.graph.extensionManager
            val extension = manager.getInstalledExtensions().firstOrNull {
                it.pkgName == packageName
            } ?: run {
                val untrusted = withTimeout(45_000L) {
                    manager.untrustedExtensionsFlow.first { extensions ->
                        extensions.any { it.pkgName == packageName }
                    }.single { it.pkgName == packageName }
                }
                assertEquals(expectedVersion, untrusted.versionName)
                // Explicit test trust is limited to disposable Android emulator data.
                manager.trust(untrusted)
                withTimeout(45_000L) {
                    manager.installedExtensionsFlow.first { extensions ->
                        extensions.any { it.pkgName == packageName }
                    }.single { it.pkgName == packageName }
                }
            }
            assertEquals(expectedVersion, extension.versionName)
            assertTrue("No internal Mihon sources", extension.sources.isNotEmpty())
            assertEquals(
                extension.sources.size,
                extension.sources.map { it.id }.distinct().size,
            )
            val registered = withTimeout(45_000L) {
                app.graph.sourceManager.sources.first { sources ->
                    extension.sources.all { found -> sources.any { it.id == found.id } }
                }
            }
            assertTrue(
                extension.sources.all { found ->
                    registered.any { it.id == found.id }
                },
            )

            // Test the Tsuzuki facade for EVERY real internal source, not just the extension loader.
            // Disabled internal languages must never leak into the product-facing Add-on snapshot.
            val disabled = app.graph.sourcePreferences.disabledSources.get()
            val expectedEnabled = extension.sources
                .filter { it.id.toString() !in disabled }
                .map { it.id }
                .toSet()
            val addon = app.graph.addonRepository.snapshot().single { it.id.value == packageName }
            assertEquals(expectedEnabled, addon.mihonSourceIds.toSet())
            assertEquals(expectedEnabled.isNotEmpty(), addon.enabled)
            val eligibleByLanguage = extension.sources
                .filter { it.id in expectedEnabled }
                .groupBy { it.lang }
            for ((language, sources) in eligibleByLanguage) {
                val facadeIds = app.graph.readingSourceGateway.listInstalled(language)
                    .map { it.sourceId }
                    .toSet()
                assertTrue(
                    "Tsuzuki source facade omitted internal sources for language $language",
                    sources.all { it.id in facadeIds },
                )
            }
            instrumentation.sendStatus(
                1,
                Bundle().apply {
                    putString(
                        "stream",
                        "EXTENSION_FIXTURE|sourceCount=" + extension.sources.size +
                            "|eligibleSources=" + expectedEnabled.size +
                            "|languages=" + eligibleByLanguage.size,
                    )
                },
            )
        }
    }

    /**
     * Opt-in, rate-limited search across a shard of *all* real internal sources. A missing title is
     * a valid EMPTY outcome; provider outages, CAPTCHA and HTTP throttling are observations,
     * not a verdict that Tsuzuki's generic adapter is broken. No title is materialized.
     */
    @Test
    fun optionalLiveSearchSourceBatch() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue(
                "Real provider requests require a separately opted-in workflow",
                arguments.getString("allowLiveProvider") == "true",
            )
            val packageName = requireNotNull(arguments.getString("fixturePackageName"))
            val start = requireNotNull(arguments.getString("sourceStart")).toInt()
            val count = requireNotNull(arguments.getString("sourceCount")).toInt()
            require(start >= 0 && count in 1..MAX_LIVE_BATCH)
            val app = instrumentation.targetContext.applicationContext as App
            val extension = withTimeout(30_000L) {
                app.graph.extensionManager.installedExtensionsFlow.first { installed ->
                    installed.any { it.pkgName == packageName }
                }.single { it.pkgName == packageName }
            }
            val sources = extension.sources.sortedBy { it.id }
            require(start + count <= sources.size) {
                "The shard range exceeds the verified internal source inventory"
            }
            val disabled = app.graph.sourcePreferences.disabledSources.get()
            var pausedForProvider = false
            for (source in sources.subList(start, start + count)) {
                val began = SystemClock.elapsedRealtime()
                val result = when {
                    pausedForProvider -> Triple("PAUSED_BACKOFF", "none", 0)
                    source.id.toString() in disabled -> Triple("SOURCE_DISABLED", "none", 0)
                    source !is CatalogueSource -> Triple("UNSUPPORTED", "none", 0)
                    else -> {
                        val language = source.lang.substringBefore("-").lowercase()
                        val query = when (language) {
                            "ja" -> "ワンパンマン"
                            "zh" -> "一拳超人"
                            "ko" -> "원펀맨"
                            "ru" -> "Ванпанчмен"
                            else -> "One Punch Man"
                        }
                        try {
                            val response = withTimeout(18_000L) {
                                withContext(Dispatchers.IO) {
                                    app.graph.readingSourceGateway.search(source.id, query)
                                }
                            }
                            response.fold(
                                onSuccess = { matches ->
                                    Triple(
                                        if (matches.isEmpty()) "EMPTY" else "RESULTS",
                                        "none",
                                        matches.size.coerceAtMost(9999),
                                    )
                                },
                                onFailure = { error ->
                                    val failure = error as? ReadingSourceSearchFailure
                                    Triple(
                                        failure?.kind?.name ?: "EXTENSION_FAILURE",
                                        failure?.httpStatus?.toString() ?: "none",
                                        0,
                                    )
                                },
                            )
                        } catch (_: TimeoutCancellationException) {
                            Triple("TIMEOUT", "none", 0)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Throwable) {
                            Triple("EXTENSION_FAILURE", "none", 0)
                        }
                    }
                }
                val elapsed = (SystemClock.elapsedRealtime() - began).coerceAtLeast(0L)
                val safeLang = source.lang.takeIf {
                    it.length in 1..15 && it.all { char -> char.isLetterOrDigit() || char == '-' }
                } ?: "und"
                instrumentation.sendStatus(
                    1,
                    Bundle().apply {
                        putString(
                            "stream",
                            "SOURCE_PROBE|sourceId=" + source.id +
                                "|lang=" + safeLang +
                                "|outcome=" + result.first +
                                "|httpStatus=" + result.second +
                                "|resultCount=" + result.third +
                                "|elapsedMs=" + elapsed,
                        )
                    },
                )
                if (result.first == "CAPTCHA_REQUIRED" ||
                    (result.first == "HTTP_RESPONSE" && result.second == "429")
                ) {
                    pausedForProvider = true
                }
                if (!pausedForProvider &&
                    result.first !in setOf("SOURCE_DISABLED", "UNSUPPORTED", "PAUSED_BACKOFF")
                ) {
                    delay(2_500L)
                }
            }
        }
    }

    private companion object {
        const val MAX_LIVE_BATCH = 12
    }
}
