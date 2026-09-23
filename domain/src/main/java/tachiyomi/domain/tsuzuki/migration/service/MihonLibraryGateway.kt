package tachiyomi.domain.tsuzuki.migration.service

import tachiyomi.domain.tsuzuki.migration.model.MihonLibrarySnapshot

interface MihonLibraryGateway {
    suspend fun snapshot(): List<MihonLibrarySnapshot>
}
