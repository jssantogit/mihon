package tachiyomi.domain.tsuzuki.sync.service

import tachiyomi.domain.tsuzuki.model.CanonicalTitle

fun interface CanonicalTitleSyncSource {
    suspend fun getAllTitles(): List<CanonicalTitle>
}
