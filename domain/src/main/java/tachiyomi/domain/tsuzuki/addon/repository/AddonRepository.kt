package tachiyomi.domain.tsuzuki.addon.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon

interface AddonRepository {
    fun observeInstalled(): Flow<List<InstalledAddon>>

    suspend fun snapshot(): List<InstalledAddon>

    suspend fun setEnabled(id: AddonId, enabled: Boolean)

    suspend fun uninstall(id: AddonId) = Unit
}
