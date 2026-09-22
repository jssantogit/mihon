package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleMergeConflict
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleMergeRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CanonicalTitleMergeRepositoryImpl(
    private val database: Database,
) : CanonicalTitleMergeRepository {

    override suspend fun convergeTo(targetId: String, localId: String) {
        require(targetId.isNotBlank()) { "Target canonical title id is required" }
        require(localId.isNotBlank()) { "Local canonical title id is required" }
        require(targetId != localId) { "Target and local canonical title ids must be different" }

        database.transaction {
            val localTitle = getTitle(localId)
                ?: throw IllegalArgumentException("Local canonical title $localId does not exist")
            val targetTitle = getTitle(targetId)

            val localLibrary = getLibraryEntry(localId)
            val targetLibrary = getLibraryEntry(targetId)
            val mergedLibrary = mergeLibraryEntries(targetId, targetLibrary, localLibrary)

            val localContentPreference = getContentPreference(localId)
            val targetContentPreference = getContentPreference(targetId)
            val mergedContentPreference = mergeContentPreferences(
                targetId = targetId,
                target = targetContentPreference,
                local = localContentPreference,
            )

            val localContinueReading = getContinueReading(localId)
            val targetContinueReading = getContinueReading(targetId)
            val mergedContinueReading = mergeContinueReading(
                targetId = targetId,
                target = targetContinueReading,
                local = localContinueReading,
            )

            val localReaderPreference = getReaderPreference(localId)
            val targetReaderPreference = getReaderPreference(targetId)
            val mergedReaderPreference = mergeReaderPreferences(
                targetId = targetId,
                target = targetReaderPreference,
                local = localReaderPreference,
            )

            val localMappings = getSourceMappings(localId)
            val targetMappings = getSourceMappings(targetId)
            validatePreferredSourceMappings(targetMappings, localMappings)

            val localChapters = getChapters(localId)
            val targetChapters = getChapters(targetId)
            validateChapterIdentities(targetChapters, localChapters)

            val localCategoryIds = getCategoryIds(localId)
            val localBindings = getContentBindings(localId)
            val localEvidence = getChapterEvidence(localId)
            val localUpdateState = getChapterUpdateState(localId)
            val localOverrides = getChapterOverrides(localId)

            if (targetTitle == null) {
                database.tsuzuki_titlesQueries.insertTsuzukiTitle(
                    id = targetId,
                    displayTitle = localTitle.displayTitle,
                    identityState = localTitle.identityState,
                    createdAt = localTitle.createdAt,
                    updatedAt = localTitle.updatedAt,
                )
            }

            mergedLibrary?.let { entry ->
                database.tsuzuki_library_entriesQueries.upsertTsuzukiLibraryEntry(
                    canonicalTitleId = entry.canonicalTitleId,
                    status = entry.status.name,
                    favorite = entry.favorite,
                    addedAt = entry.addedAt,
                    updatedAt = entry.updatedAt,
                )
            }
            if (localLibrary != null) {
                database.tsuzuki_library_entriesQueries.deleteTsuzukiLibraryEntry(localId)
            }

            localCategoryIds.forEach { categoryId ->
                database.tsuzuki_library_categoriesQueries.insertTsuzukiLibraryCategory(
                    canonicalTitleId = targetId,
                    categoryId = categoryId,
                )
            }
            if (localCategoryIds.isNotEmpty()) {
                database.tsuzuki_library_categoriesQueries.deleteTsuzukiLibraryCategoriesByTitle(localId)
            }

            database.tsuzuki_external_identitiesQueries.rekeyTsuzukiExternalIdentities(
                targetId = targetId,
                localId = localId,
            )

            localMappings.forEach { mapping ->
                database.tsuzuki_source_mappingsQueries.upsertTsuzukiSourceMapping(
                    id = mapping.id,
                    canonicalTitleId = targetId,
                    mihonMangaId = mapping.mihonMangaId,
                    sourceId = mapping.sourceId,
                    sourceUrl = mapping.sourceUrl,
                    language = mapping.language,
                    matchConfidence = mapping.matchConfidence,
                    verifiedByUser = mapping.verifiedByUser,
                    availability = mapping.availability,
                    preferredOverride = mapping.preferredOverride,
                    createdAt = mapping.createdAt,
                    updatedAt = mapping.updatedAt,
                )
            }

            localChapters.forEach { chapter ->
                database.tsuzuki_canonical_chaptersQueries.upsertTsuzukiCanonicalChapter(
                    id = chapter.id,
                    canonicalTitleId = targetId,
                    displayNumber = chapter.displayNumber,
                    volume = chapter.volume,
                    title = chapter.title,
                    type = chapter.type,
                    baseNumber = chapter.baseNumber,
                    part = chapter.part,
                    alphaSuffix = chapter.alphaSuffix,
                    confidence = chapter.confidence,
                    createdAt = chapter.createdAt,
                    updatedAt = chapter.updatedAt,
                    confirmationState = chapter.confirmationState,
                )
            }

            localBindings.forEach { binding ->
                database.tsuzuki_content_bindingsQueries.upsertTsuzukiContentBinding(
                    id = binding.id,
                    canonicalTitleId = targetId,
                    addonId = binding.addonId,
                    providerTitleKey = binding.providerTitleKey,
                    matchConfidence = binding.matchConfidence,
                    verifiedByUser = binding.verifiedByUser,
                    availability = binding.availability,
                    runtimePayload = binding.runtimePayload,
                    createdAt = binding.createdAt,
                    updatedAt = binding.updatedAt,
                )
            }

            localEvidence.forEach { evidence ->
                database.tsuzuki_chapter_evidenceQueries.upsertTsuzukiChapterEvidence(
                    id = evidence.id,
                    canonicalTitleId = targetId,
                    producerKind = evidence.producerKind,
                    producerId = evidence.producerId,
                    externalChapterKey = evidence.externalChapterKey,
                    rawLabel = evidence.rawLabel,
                    rawNumber = evidence.rawNumber,
                    volume = evidence.volume,
                    title = evidence.title,
                    observedAt = evidence.observedAt,
                    confidence = evidence.confidence,
                    authorityClass = evidence.authorityClass,
                    mappedCanonicalChapterId = evidence.mappedCanonicalChapterId,
                    rawMetadata = evidence.rawMetadata,
                )
            }

            localUpdateState.forEach { update ->
                database.tsuzuki_chapter_update_stateQueries.upsertTsuzukiChapterUpdateState(
                    canonicalChapterId = update.canonicalChapterId,
                    canonicalTitleId = targetId,
                    firstSeenAt = update.firstSeenAt,
                    acknowledgedAt = update.acknowledgedAt,
                )
            }

            mergedContentPreference?.let { preference ->
                database.tsuzuki_content_preferencesQueries.upsertTsuzukiContentPreference(
                    canonicalTitleId = targetId,
                    preferredAddonId = preference.preferredAddonId,
                    preferredLanguage = preference.preferredLanguage,
                    updatedAt = preference.updatedAt,
                )
            }
            if (localContentPreference != null) {
                database.tsuzuki_content_preferencesQueries.deleteTsuzukiContentPreference(localId)
            }

            mergedContinueReading?.let { state ->
                database.tsuzuki_continue_reading_stateQueries.upsertTsuzukiContinueReadingState(
                    canonicalTitleId = targetId,
                    hiddenAt = state.hiddenAt,
                )
            }
            if (localContinueReading != null) {
                database.tsuzuki_continue_reading_stateQueries.deleteTsuzukiContinueReadingState(localId)
            }

            mergedReaderPreference?.let { preference ->
                database.tsuzuki_reader_preferencesQueries.upsertTsuzukiReaderPreference(
                    canonicalTitleId = targetId,
                    automaticFallback = preference.automaticFallback,
                    updatedAt = preference.updatedAt,
                )
            }
            if (localReaderPreference != null) {
                database.tsuzuki_reader_preferencesQueries.deleteTsuzukiReaderPreference(localId)
            }

            localOverrides.forEach { override ->
                database.tsuzuki_chapter_overridesQueries.upsertTsuzukiChapterOverride(
                    id = override.id,
                    canonicalTitleId = targetId,
                    canonicalChapterKey = override.canonicalChapterKey,
                    sourceId = override.sourceId,
                    sourceTitleUrl = override.sourceTitleUrl,
                    sourceChapterId = override.sourceChapterId,
                    kind = override.kind,
                    payloadJson = override.payloadJson,
                    schemaVersion = override.schemaVersion,
                    revision = override.revision,
                    createdAt = override.createdAt,
                    updatedAt = override.updatedAt,
                    deletedAt = override.deletedAt,
                )
            }

            database.tsuzuki_titlesQueries.deleteTsuzukiTitle(localId)
        }
    }

    private suspend fun getTitle(id: String): TitleRow? =
        database.tsuzuki_titlesQueries
            .getTsuzukiTitleById(id) { titleId, displayTitle, identityState, createdAt, updatedAt ->
                TitleRow(titleId, displayTitle, identityState, createdAt, updatedAt)
            }
            .awaitAsOneOrNull()

    private suspend fun getLibraryEntry(id: String): CanonicalLibraryEntry? =
        database.tsuzuki_library_entriesQueries
            .getTsuzukiLibraryEntry(id) { canonicalTitleId, status, favorite, addedAt, updatedAt ->
                CanonicalLibraryEntry(
                    canonicalTitleId = canonicalTitleId,
                    status = LibraryStatus.valueOf(status),
                    favorite = favorite,
                    addedAt = addedAt,
                    updatedAt = updatedAt,
                )
            }
            .awaitAsOneOrNull()

    private fun mergeLibraryEntries(
        targetId: String,
        target: CanonicalLibraryEntry?,
        local: CanonicalLibraryEntry?,
    ): CanonicalLibraryEntry? {
        if (local == null) return target
        if (target == null) return local.copy(canonicalTitleId = targetId)

        val status = when {
            target.status == local.status -> target.status
            target.status == LibraryStatus.PLANNING -> local.status
            local.status == LibraryStatus.PLANNING -> target.status
            else -> throw CanonicalTitleMergeConflict.LibraryStatusConflict(target.status, local.status)
        }
        return CanonicalLibraryEntry(
            canonicalTitleId = targetId,
            status = status,
            favorite = target.favorite || local.favorite,
            addedAt = minOf(target.addedAt, local.addedAt),
            updatedAt = maxOf(target.updatedAt, local.updatedAt),
        )
    }

    private suspend fun getContentPreference(id: String): ContentPreferenceRow? =
        database.tsuzuki_content_preferencesQueries
            .getTsuzukiContentPreference(id) { canonicalTitleId, preferredAddonId, preferredLanguage, updatedAt ->
                ContentPreferenceRow(canonicalTitleId, preferredAddonId, preferredLanguage, updatedAt)
            }
            .awaitAsOneOrNull()

    private fun mergeContentPreferences(
        targetId: String,
        target: ContentPreferenceRow?,
        local: ContentPreferenceRow?,
    ): ContentPreferenceRow? {
        if (local == null) return target
        if (target == null) return local.copy(canonicalTitleId = targetId)

        val targetAddon = target.preferredAddonId
        val localAddon = local.preferredAddonId
        if (targetAddon != null && localAddon != null && targetAddon != localAddon) {
            throw CanonicalTitleMergeConflict.PreferredAddonConflict(targetAddon, localAddon)
        }
        val targetLanguage = target.preferredLanguage
        val localLanguage = local.preferredLanguage
        if (targetLanguage != null && localLanguage != null &&
            !targetLanguage.equals(localLanguage, ignoreCase = true)
        ) {
            throw CanonicalTitleMergeConflict.PreferredLanguageConflict(targetLanguage, localLanguage)
        }
        return ContentPreferenceRow(
            canonicalTitleId = targetId,
            preferredAddonId = targetAddon ?: localAddon,
            preferredLanguage = targetLanguage ?: localLanguage,
            updatedAt = maxOf(target.updatedAt, local.updatedAt),
        )
    }

    private suspend fun getContinueReading(id: String): ContinueReadingRow? =
        database.tsuzuki_continue_reading_stateQueries
            .getTsuzukiContinueReadingState(id) { canonicalTitleId, hiddenAt ->
                ContinueReadingRow(canonicalTitleId, hiddenAt)
            }
            .awaitAsOneOrNull()

    private fun mergeContinueReading(
        targetId: String,
        target: ContinueReadingRow?,
        local: ContinueReadingRow?,
    ): ContinueReadingRow? {
        if (local == null) return target
        if (target == null) return local.copy(canonicalTitleId = targetId)
        if (target.hiddenAt != local.hiddenAt) {
            throw CanonicalTitleMergeConflict.ContinueReadingConflict()
        }
        return target
    }

    private suspend fun getReaderPreference(id: String): ReaderPreferenceRow? =
        database.tsuzuki_reader_preferencesQueries
            .getTsuzukiReaderPreference(id) { canonicalTitleId, automaticFallback, updatedAt ->
                ReaderPreferenceRow(canonicalTitleId, automaticFallback, updatedAt)
            }
            .awaitAsOneOrNull()

    private fun mergeReaderPreferences(
        targetId: String,
        target: ReaderPreferenceRow?,
        local: ReaderPreferenceRow?,
    ): ReaderPreferenceRow? {
        if (local == null) return target
        if (target == null) return local.copy(canonicalTitleId = targetId)
        if (target.automaticFallback != local.automaticFallback) {
            throw CanonicalTitleMergeConflict.ReaderPreferenceConflict()
        }
        return ReaderPreferenceRow(
            canonicalTitleId = targetId,
            automaticFallback = target.automaticFallback,
            updatedAt = maxOf(target.updatedAt, local.updatedAt),
        )
    }

    private suspend fun getSourceMappings(id: String): List<SourceMappingRow> =
        database.tsuzuki_source_mappingsQueries
            .getTsuzukiSourceMappingsByTitle(id) {
                    mappingId,
                    canonicalTitleId,
                    mihonMangaId,
                    sourceId,
                    sourceUrl,
                    language,
                    matchConfidence,
                    verifiedByUser,
                    availability,
                    preferredOverride,
                    createdAt,
                    updatedAt,
                ->
                SourceMappingRow(
                    id = mappingId,
                    canonicalTitleId = canonicalTitleId,
                    mihonMangaId = mihonMangaId,
                    sourceId = sourceId,
                    sourceUrl = sourceUrl,
                    language = language,
                    matchConfidence = matchConfidence,
                    verifiedByUser = verifiedByUser,
                    availability = availability,
                    preferredOverride = preferredOverride,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                )
            }
            .awaitAsList()

    private fun validatePreferredSourceMappings(
        target: List<SourceMappingRow>,
        local: List<SourceMappingRow>,
    ) {
        val targetPreferred = target.singleOrNull { it.preferredOverride }
        val localPreferred = local.singleOrNull { it.preferredOverride }
        if (targetPreferred != null && localPreferred != null) {
            throw CanonicalTitleMergeConflict.PreferredSourceConflict(
                targetMappingId = targetPreferred.id,
                localMappingId = localPreferred.id,
            )
        }
    }

    private suspend fun getChapters(id: String): List<ChapterRow> =
        database.tsuzuki_canonical_chaptersQueries
            .getTsuzukiCanonicalChaptersByTitle(id) {
                    chapterId,
                    canonicalTitleId,
                    displayNumber,
                    volume,
                    title,
                    type,
                    baseNumber,
                    part,
                    alphaSuffix,
                    confidence,
                    createdAt,
                    updatedAt,
                    confirmationState,
                ->
                ChapterRow(
                    id = chapterId,
                    canonicalTitleId = canonicalTitleId,
                    displayNumber = displayNumber,
                    volume = volume,
                    title = title,
                    type = type,
                    baseNumber = baseNumber,
                    part = part,
                    alphaSuffix = alphaSuffix,
                    confidence = confidence,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    confirmationState = confirmationState,
                )
            }
            .awaitAsList()

    private fun validateChapterIdentities(
        target: List<ChapterRow>,
        local: List<ChapterRow>,
    ) {
        val targetByIdentity = target
            .mapNotNull { chapter -> chapter.identity.takeIf { it.isSpecific }?.let { it to chapter } }
            .toMap()
        local.forEach { localChapter ->
            val identity = localChapter.identity.takeIf { it.isSpecific } ?: return@forEach
            val targetChapter = targetByIdentity[identity] ?: return@forEach
            throw CanonicalTitleMergeConflict.ChapterIdentityConflict(
                targetChapterId = targetChapter.id,
                localChapterId = localChapter.id,
                chapterKey = identity.sortKey,
            )
        }
    }

    private suspend fun getCategoryIds(id: String): List<Long> =
        database.tsuzuki_library_categoriesQueries
            .getTsuzukiLibraryCategoriesByTitle(id) { categoryId, _, _, _ -> categoryId }
            .awaitAsList()

    private suspend fun getContentBindings(id: String): List<ContentBindingRow> =
        database.tsuzuki_content_bindingsQueries
            .getTsuzukiContentBindingsByTitle(id) {
                    bindingId,
                    canonicalTitleId,
                    addonId,
                    providerTitleKey,
                    matchConfidence,
                    verifiedByUser,
                    availability,
                    runtimePayload,
                    createdAt,
                    updatedAt,
                ->
                ContentBindingRow(
                    bindingId,
                    canonicalTitleId,
                    addonId,
                    providerTitleKey,
                    matchConfidence,
                    verifiedByUser,
                    availability,
                    runtimePayload,
                    createdAt,
                    updatedAt,
                )
            }
            .awaitAsList()

    private suspend fun getChapterEvidence(id: String): List<EvidenceRow> =
        database.tsuzuki_chapter_evidenceQueries
            .getTsuzukiChapterEvidenceByTitle(id) {
                    evidenceId,
                    canonicalTitleId,
                    producerKind,
                    producerId,
                    externalChapterKey,
                    rawLabel,
                    rawNumber,
                    volume,
                    title,
                    observedAt,
                    confidence,
                    authorityClass,
                    mappedCanonicalChapterId,
                    rawMetadata,
                ->
                EvidenceRow(
                    evidenceId,
                    canonicalTitleId,
                    producerKind,
                    producerId,
                    externalChapterKey,
                    rawLabel,
                    rawNumber,
                    volume,
                    title,
                    observedAt,
                    confidence,
                    authorityClass,
                    mappedCanonicalChapterId,
                    rawMetadata,
                )
            }
            .awaitAsList()

    private suspend fun getChapterUpdateState(id: String): List<UpdateStateRow> =
        database.tsuzuki_chapter_update_stateQueries
            .getTsuzukiChapterUpdateStateByTitle(id) {
                    canonicalChapterId,
                    canonicalTitleId,
                    firstSeenAt,
                    acknowledgedAt,
                ->
                UpdateStateRow(canonicalChapterId, canonicalTitleId, firstSeenAt, acknowledgedAt)
            }
            .awaitAsList()

    private suspend fun getChapterOverrides(id: String): List<OverrideRow> =
        database.tsuzuki_chapter_overridesQueries
            .getAllTsuzukiChapterOverrides {
                    overrideId,
                    canonicalTitleId,
                    canonicalChapterKey,
                    sourceId,
                    sourceTitleUrl,
                    sourceChapterId,
                    kind,
                    payloadJson,
                    schemaVersion,
                    revision,
                    createdAt,
                    updatedAt,
                    deletedAt,
                ->
                OverrideRow(
                    overrideId,
                    canonicalTitleId,
                    canonicalChapterKey,
                    sourceId,
                    sourceTitleUrl,
                    sourceChapterId,
                    kind,
                    payloadJson,
                    schemaVersion,
                    revision,
                    createdAt,
                    updatedAt,
                    deletedAt,
                )
            }
            .awaitAsList()
            .filter { it.canonicalTitleId == id }

    private data class TitleRow(
        val id: String,
        val displayTitle: String,
        val identityState: String,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private data class ContentPreferenceRow(
        val canonicalTitleId: String,
        val preferredAddonId: String?,
        val preferredLanguage: String?,
        val updatedAt: Long,
    )

    private data class ContinueReadingRow(
        val canonicalTitleId: String,
        val hiddenAt: Long?,
    )

    private data class ReaderPreferenceRow(
        val canonicalTitleId: String,
        val automaticFallback: Boolean,
        val updatedAt: Long,
    )

    private data class SourceMappingRow(
        val id: String,
        val canonicalTitleId: String,
        val mihonMangaId: Long?,
        val sourceId: Long,
        val sourceUrl: String,
        val language: String,
        val matchConfidence: Double?,
        val verifiedByUser: Boolean,
        val availability: String,
        val preferredOverride: Boolean,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private data class ChapterRow(
        val id: String,
        val canonicalTitleId: String,
        val displayNumber: String,
        val volume: Long?,
        val title: String?,
        val type: String,
        val baseNumber: Long?,
        val part: Long?,
        val alphaSuffix: String?,
        val confidence: Double,
        val createdAt: Long,
        val updatedAt: Long,
        val confirmationState: String,
    ) {
        val identity: CanonicalChapterIdentity
            get() = CanonicalChapterIdentity(
                type = CanonicalChapterType.valueOf(type),
                baseNumber = baseNumber?.toInt(),
                part = part?.toInt(),
                alphaSuffix = alphaSuffix,
            )
    }

    private data class ContentBindingRow(
        val id: String,
        val canonicalTitleId: String,
        val addonId: String,
        val providerTitleKey: String,
        val matchConfidence: Double,
        val verifiedByUser: Boolean,
        val availability: String,
        val runtimePayload: ByteArray,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private data class EvidenceRow(
        val id: String,
        val canonicalTitleId: String,
        val producerKind: String,
        val producerId: String,
        val externalChapterKey: String?,
        val rawLabel: String,
        val rawNumber: Double?,
        val volume: Long?,
        val title: String?,
        val observedAt: Long,
        val confidence: Double,
        val authorityClass: String,
        val mappedCanonicalChapterId: String?,
        val rawMetadata: ByteArray,
    )

    private data class UpdateStateRow(
        val canonicalChapterId: String,
        val canonicalTitleId: String,
        val firstSeenAt: Long,
        val acknowledgedAt: Long?,
    )

    private data class OverrideRow(
        val id: String,
        val canonicalTitleId: String,
        val canonicalChapterKey: String?,
        val sourceId: Long?,
        val sourceTitleUrl: String?,
        val sourceChapterId: String?,
        val kind: String,
        val payloadJson: String,
        val schemaVersion: Long,
        val revision: Long,
        val createdAt: Long,
        val updatedAt: Long,
        val deletedAt: Long?,
    )
}
