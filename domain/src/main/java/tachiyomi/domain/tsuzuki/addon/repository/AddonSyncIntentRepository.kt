package tachiyomi.domain.tsuzuki.addon.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.AddonSyncIntent

interface AddonSyncIntentRepository {
    fun observe(): Flow<AddonSyncIntent>

    suspend fun get(): AddonSyncIntent

    suspend fun set(intent: AddonSyncIntent)

    suspend fun recordEnabled(id: AddonId, enabled: Boolean)

    suspend fun removeDesired(id: AddonId)
}
