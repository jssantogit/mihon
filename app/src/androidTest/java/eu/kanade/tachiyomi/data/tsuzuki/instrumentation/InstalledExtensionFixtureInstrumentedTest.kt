package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.App
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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
}
