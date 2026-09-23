package tachiyomi.domain.tsuzuki.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import java.util.UUID
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
    ): CanonicalTitle {
        repository.getByExternalIdentity(provider, externalId)?.let { return it }

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
        return repository.getOrCreateByExternalIdentity(title, identity)
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
