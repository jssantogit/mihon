package eu.kanade.tachiyomi.data.tsuzuki.addon

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadArtifact
import tachiyomi.domain.tsuzuki.download.repository.CanonicalDownloadRepository

class LocalContentProviderTest {

    @Test
    fun canonicalDownloadIsExposedWithoutOriginAddonInstalled() = runTest {
        val repository = FakeRepository(
            CanonicalDownloadArtifact(
                canonicalChapterId = "chapter-1",
                localUri = "content://downloads/chapter-1.cbz",
                format = "CBZ",
                originatingAddonId = AddonId("removed-addon"),
                originatingOptionKey = "remote-option",
                completedAt = 100L,
                checksum = null,
            ),
        )
        val provider = LocalContentProvider(repository)

        val options = provider.resolve("title-1", "chapter-1").getOrThrow()

        options.size shouldBe 1
        options.single().addonId shouldBe LocalContentProvider.ADDON_ID
        options.single().delivery shouldBe ContentDelivery.LocalArchive("content://downloads/chapter-1.cbz")
    }

    @Test
    fun directoryArtifactMapsToLocalDirectoryDelivery() = runTest {
        val provider = LocalContentProvider(
            FakeRepository(
                CanonicalDownloadArtifact(
                    canonicalChapterId = "chapter-1",
                    localUri = "content://downloads/chapter-1",
                    format = "DIRECTORY",
                    originatingAddonId = null,
                    originatingOptionKey = null,
                    completedAt = 100L,
                    checksum = null,
                ),
            ),
        )

        provider.resolve("title-1", "chapter-1").getOrThrow().single().delivery shouldBe
            ContentDelivery.LocalDirectory("content://downloads/chapter-1")
    }

    private class FakeRepository(
        private var artifact: CanonicalDownloadArtifact?,
    ) : CanonicalDownloadRepository {
        override suspend fun get(canonicalChapterId: String): CanonicalDownloadArtifact? =
            artifact?.takeIf { it.canonicalChapterId == canonicalChapterId }

        override suspend fun upsert(artifact: CanonicalDownloadArtifact) {
            this.artifact = artifact
        }

        override suspend fun delete(canonicalChapterId: String) {
            if (artifact?.canonicalChapterId == canonicalChapterId) artifact = null
        }

        override suspend fun deleteOriginMetadata(addonId: AddonId) = Unit
    }
}