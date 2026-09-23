package eu.kanade.tachiyomi.data.tsuzuki.addon

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.AddonSyncIntent
import tachiyomi.domain.tsuzuki.addon.repository.AddonSyncIntentRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PreferenceAddonSyncIntentRepository(
    preferenceStore: PreferenceStore,
) : AddonSyncIntentRepository {

    private val intentPreference = preferenceStore.getObjectFromString(
        key = "tsuzuki_addon_sync_intent",
        defaultValue = AddonSyncIntent(),
        serializer = ::serialize,
        deserializer = ::deserialize,
    )

    override fun observe(): Flow<AddonSyncIntent> =
        intentPreference.changes()
            .onStart { emit(intentPreference.get()) }
            .distinctUntilChanged()

    override suspend fun get(): AddonSyncIntent = intentPreference.get()

    override suspend fun set(intent: AddonSyncIntent) {
        intentPreference.set(intent.normalized())
    }

    override suspend fun recordEnabled(id: AddonId, enabled: Boolean) {
        val current = intentPreference.get()
        set(
            current.copy(
                desiredPackageIds = current.desiredPackageIds + id.value,
                enabledPackageIds = if (enabled) {
                    current.enabledPackageIds + id.value
                } else {
                    current.enabledPackageIds - id.value
                },
            ),
        )
    }

    override suspend fun removeDesired(id: AddonId) {
        val current = intentPreference.get()
        set(
            current.copy(
                desiredPackageIds = current.desiredPackageIds - id.value,
                enabledPackageIds = current.enabledPackageIds - id.value,
            ),
        )
    }

    private fun AddonSyncIntent.normalized(): AddonSyncIntent {
        val desired = desiredPackageIds
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        return AddonSyncIntent(
            desiredPackageIds = desired,
            enabledPackageIds = enabledPackageIds
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
                .intersect(desired),
        )
    }

    private fun serialize(intent: AddonSyncIntent): String {
        val normalized = intent.normalized()
        return buildString {
            append(normalized.desiredPackageIds.sorted().joinToString(ITEM_SEPARATOR))
            append(SET_SEPARATOR)
            append(normalized.enabledPackageIds.sorted().joinToString(ITEM_SEPARATOR))
        }
    }

    private fun deserialize(raw: String): AddonSyncIntent {
        val parts = raw.split(SET_SEPARATOR, limit = 2)
        val desired = parts.getOrNull(0).toPackageSet()
        val enabled = parts.getOrNull(1).toPackageSet().intersect(desired)
        return AddonSyncIntent(
            desiredPackageIds = desired,
            enabledPackageIds = enabled,
        )
    }

    private fun String?.toPackageSet(): Set<String> =
        this
            ?.split(ITEM_SEPARATOR)
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty()

    private companion object {
        const val SET_SEPARATOR = "\u001E"
        const val ITEM_SEPARATOR = "\u001F"
    }
}
