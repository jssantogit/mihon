package tachiyomi.data.tsuzuki.content

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ContentBindingRepositoryImpl(
    private val database: Database,
) : ContentBindingRepository {

    override suspend fun get(canonicalTitleId: String, addonId: AddonId): ContentBinding? {
        return database.tsuzuki_content_bindingsQueries
            .getTsuzukiContentBinding(
                canonicalTitleId = canonicalTitleId,
                addonId = addonId.value,
                mapper = ::mapBinding,
            )
            .awaitAsOneOrNull()
    }

    override suspend fun getByTitle(canonicalTitleId: String): List<ContentBinding> {
        return database.tsuzuki_content_bindingsQueries
            .getTsuzukiContentBindingsByTitle(canonicalTitleId, ::mapBinding)
            .awaitAsList()
    }

    override suspend fun upsert(binding: ContentBinding) {
        database.tsuzuki_content_bindingsQueries.upsertTsuzukiContentBinding(
            id = binding.id,
            canonicalTitleId = binding.canonicalTitleId,
            addonId = binding.addonId.value,
            providerTitleKey = binding.providerTitleKey,
            matchConfidence = binding.matchConfidence,
            verifiedByUser = binding.verifiedByUser,
            availability = binding.availability.name,
            runtimePayload = binding.runtimePayload,
            createdAt = binding.createdAt,
            updatedAt = binding.updatedAt,
        )
    }

    override suspend fun markUnavailable(bindingId: String, updatedAt: Long) {
        database.tsuzuki_content_bindingsQueries.markTsuzukiContentBindingUnavailable(
            id = bindingId,
            updatedAt = updatedAt,
        )
    }

    private fun mapBinding(
        id: String,
        canonicalTitleId: String,
        addonId: String,
        providerTitleKey: String,
        matchConfidence: Double,
        verifiedByUser: Boolean,
        availability: String,
        runtimePayload: ByteArray,
        createdAt: Long,
        updatedAt: Long,
    ) = ContentBinding(
        id = id,
        canonicalTitleId = canonicalTitleId,
        addonId = AddonId(addonId),
        providerTitleKey = providerTitleKey,
        matchConfidence = matchConfidence,
        verifiedByUser = verifiedByUser,
        availability = ContentBindingAvailability.valueOf(availability),
        runtimePayload = runtimePayload,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
