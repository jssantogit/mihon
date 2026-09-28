package tachiyomi.domain.tsuzuki.addon.repository

import tachiyomi.domain.tsuzuki.addon.AddonId

/** Current loaded internal-source eligibility, kept separate from the product-facing Add-on model. */
data class AddonSourceEligibility(
    val sourceId: Long,
    val language: String,
    val enabled: Boolean,
)

/** Read-only capability used for safe chapter resolution and explicit diagnostics. */
fun interface AddonSourceEligibilityRepository {
    suspend fun getByAddonId(addonId: AddonId): List<AddonSourceEligibility>
}
