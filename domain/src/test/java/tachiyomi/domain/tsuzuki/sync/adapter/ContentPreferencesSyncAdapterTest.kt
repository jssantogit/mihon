package tachiyomi.domain.tsuzuki.sync.adapter

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.ContentPreference
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class ContentPreferencesSyncAdapterTest {

    @Test
    fun `title addon preference and global fallback languages round trip`() = runTest {
        val repository = FakeContentPreferenceRepository(
            listOf(
                ContentPreference(
                    canonicalTitleId = "title-1",
                    preferredAddonId = AddonId("pkg.one"),
                    updatedAt = 30,
                    preferredLanguage = "pt-BR",
                ),
            ),
        )
        val readerPreferences = CanonicalReaderPreferences(InMemoryPreferenceStore())
        readerPreferences.automaticFallback.set(true)
        readerPreferences.preferredLanguages.set(listOf("pt-BR", "en"))

        val adapter = ContentPreferencesSyncAdapter(
            repository = repository,
            readerPreferences = readerPreferences,
            revisionSource = FakeRevisionSource,
            clock = FakeClock,
        )

        val exported = adapter.exportDocument()

        exported.kind shouldBe SyncDocumentKind.CONTENT_PREFERENCES
        exported.records.getValue("title:title-1")
            .fields["preferredAddonId"] shouldBe JsonPrimitive("pkg.one")
        exported.records.getValue("title:title-1")
            .fields["preferredLanguage"] shouldBe JsonPrimitive("pt-BR")
        exported.records.getValue("global")
            .fields["automaticFallback"] shouldBe JsonPrimitive(true)
        exported.records.getValue("global")
            .fields["preferredLanguages"] shouldBe JsonArray(
            listOf(JsonPrimitive("pt-BR"), JsonPrimitive("en")),
        )

        val remote = exported.copy(
            records = mapOf(
                "title:title-1" to exported.records.getValue("title:title-1").copy(
                    updatedAtEpochMillis = 80,
                    fields = buildJsonObject {
                        put("recordType", JsonPrimitive("title"))
                        put("canonicalTitleId", JsonPrimitive("title-1"))
                        put("preferredAddonId", JsonNull)
                        put("preferredLanguage", JsonPrimitive("ja"))
                    },
                ),
                "global" to exported.records.getValue("global").copy(
                    updatedAtEpochMillis = 81,
                    fields = buildJsonObject {
                        put("recordType", JsonPrimitive("global"))
                        put("automaticFallback", JsonPrimitive(false))
                        put(
                            "preferredLanguages",
                            JsonArray(listOf(JsonPrimitive("ja"), JsonPrimitive("en"))),
                        )
                    },
                ),
            ),
        )

        adapter.applyDocument(remote)

        repository.get("title-1")?.preferredAddonId shouldBe null
        repository.get("title-1")?.preferredLanguage shouldBe "ja"
        repository.get("title-1")?.updatedAt shouldBe 80
        readerPreferences.automaticFallback.get() shouldBe false
        readerPreferences.preferredLanguages.get() shouldBe listOf("ja", "en")
    }

    private class FakeContentPreferenceRepository(
        initial: List<ContentPreference>,
    ) : ContentPreferenceRepository {
        private val values = MutableStateFlow(initial)

        override suspend fun get(canonicalTitleId: String): ContentPreference? =
            values.value.firstOrNull { it.canonicalTitleId == canonicalTitleId }

        override fun observe(canonicalTitleId: String): Flow<ContentPreference?> =
            MutableStateFlow(values.value.firstOrNull { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getAll(): List<ContentPreference> = values.value

        override suspend fun upsert(preference: ContentPreference) {
            values.value = values.value.filterNot {
                it.canonicalTitleId == preference.canonicalTitleId
            } + preference
        }

        override suspend fun delete(canonicalTitleId: String) {
            values.value = values.value.filterNot {
                it.canonicalTitleId == canonicalTitleId
            }
        }
    }

    private data object FakeClock : SyncClock {
        override fun nowEpochMillis(): Long = 100
    }

    private data object FakeRevisionSource : SyncRevisionSource {
        override val deviceId: String = "device"
        private var sequence = 0L
        override fun nextRevision() = SyncRevision(deviceId, ++sequence)
    }
}
