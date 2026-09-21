package tachiyomi.domain.tsuzuki.content.repository

import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.ContentBinding

interface ContentBindingRepository {
    suspend fun get(canonicalTitleId: String, addonId: AddonId): ContentBinding?
    suspend fun getByTitle(canonicalTitleId: String): List<ContentBinding>
    suspend fun upsert(binding: ContentBinding)
    suspend fun markUnavailable(bindingId: String, updatedAt: Long)
}
