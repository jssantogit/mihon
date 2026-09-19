package tachiyomi.domain.tsuzuki.collections.portable

import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection

data class PortableCollectionsDocument(
    val schemaVersion: Int = CURRENT_PORTABLE_COLLECTIONS_SCHEMA_VERSION,
    val collections: List<TsuzukiCollection>,
    val folders: List<CollectionFolder>,
    val lists: List<CollectionList>,
) {
    init {
        require(schemaVersion > 0) { "Portable Collections schemaVersion must be positive" }
    }
}

interface CollectionPortableCodec {
    fun encode(document: PortableCollectionsDocument): String
    fun decode(encoded: String): PortableCollectionsDocument
}

const val CURRENT_PORTABLE_COLLECTIONS_SCHEMA_VERSION: Int = 1
