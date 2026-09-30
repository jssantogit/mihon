package tachiyomi.domain.tsuzuki.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

class MaterializeCanonicalTitle internal constructor(
    private val repository: CanonicalTitleRepository,
    private val idFactory: () -> String,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        repository: CanonicalTitleRepository,
    ) : this(
        repository = repository,
        idFactory = { UUID.randomUUID().toString() },
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun fromCatalog(
        displayTitle: String,
        provider: String,
        externalId: String,
        externalIds: Map<String, String> = emptyMap(),
    ): CanonicalTitle {
        val mappedExisting = mutableListOf<CanonicalTitle>()
        for ((mappedProvider, mappedExternalId) in externalIds) {
            if (
                mappedProvider.isBlank() ||
                mappedExternalId.isBlank() ||
                (mappedProvider == provider && mappedExternalId == externalId)
            ) {
                continue
            }
            repository.getByExternalIdentity(mappedProvider, mappedExternalId)?.let(mappedExisting::add)
        }
        val distinctMappedExisting = mappedExisting.distinctBy(CanonicalTitle::id)

        val primaryExisting = repository.getByExternalIdentity(provider, externalId)
        val resolved = when {
            primaryExisting != null -> primaryExisting
            distinctMappedExisting.size == 1 -> {
                distinctMappedExisting.single().also { existing ->
                    attachIdentityIfSafe(
                        title = existing,
                        provider = provider,
                        externalId = externalId,
                    )
                }
            }
            else -> {
                val now = clock()
                val title = CanonicalTitle(
                    id = idFactory(),
                    displayTitle = displayTitle,
                    identityState = CanonicalIdentityState.RESOLVED,
                    createdAt = now,
                    updatedAt = now,
                )
                val identity = ExternalIdentity(
                    canonicalTitleId = title.id,
                    provider = provider,
                    externalId = externalId,
                    verified = true,
                    createdAt = now,
                )
                repository.getOrCreateByExternalIdentity(title, identity)
            }
        }

        for ((mappedProvider, mappedExternalId) in externalIds) {
            if (
                mappedProvider.isNotBlank() &&
                mappedExternalId.isNotBlank() &&
                !(mappedProvider == provider && mappedExternalId == externalId)
            ) {
                attachIdentityIfSafe(
                    title = resolved,
                    provider = mappedProvider,
                    externalId = mappedExternalId,
                )
            }
        }

        return resolved
    }

    private suspend fun attachIdentityIfSafe(
        title: CanonicalTitle,
        provider: String,
        externalId: String,
    ) {
        val existing = repository.getByExternalIdentity(provider, externalId)
        if (existing != null) {
            return
        }

        val identity = ExternalIdentity(
            canonicalTitleId = title.id,
            provider = provider,
            externalId = externalId,
            verified = true,
            createdAt = clock(),
        )
        try {
            repository.addExternalIdentity(identity)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val raced = repository.getByExternalIdentity(provider, externalId)
            if (raced == null) throw error
        }
    }

    suspend fun fromSource(displayTitle: String): CanonicalTitle {
        val now = clock()
        return CanonicalTitle(
            id = idFactory(),
            displayTitle = displayTitle,
            identityState = CanonicalIdentityState.SOURCE_ONLY,
            createdAt = now,
            updatedAt = now,
        ).also { repository.insert(it) }
    }
}
