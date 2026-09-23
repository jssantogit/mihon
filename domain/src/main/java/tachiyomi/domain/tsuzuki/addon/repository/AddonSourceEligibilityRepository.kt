package tachiyomi.domain.tsuzuki.addon.repository

import tachiyomi.domain.tsuzuki.addon.AddonId

/** Diagnostic-only source state; not part of the product-facing installed add-on model. */
data class AddonSourceEligibility(
    val sourceId: Long,
    val language: String,
    val enabled: Boolean,
)

/** Optional read-only capability used only while chapter diagnostics are explicitly active. */
fun interface AddonSourceEligibilityRepository {
    suspend fun getByAddonId(addonId: AddonId): List<AddonSourceEligibility>
}
