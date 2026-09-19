package tachiyomi.domain.tsuzuki.collections.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.portable.CollectionPortableCodec
import tachiyomi.domain.tsuzuki.collections.portable.PortableCollectionsDocument
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore

@Inject
class ExportCollections(
    private val store: CollectionStore,
    private val codec: CollectionPortableCodec,
) {

    suspend fun execute(
        includeSystem: Boolean = false,
    ): String {
        val collections = store.getCollections()
            .filter { includeSystem || it.origin == CollectionOrigin.USER }

        val folders = collections.flatMap { collection ->
            store.getFolders(collection.id)
        }

        val lists = folders.flatMap { folder ->
            store.getLists(folder.id)
        }

        return codec.encode(
            PortableCollectionsDocument(
                collections = collections,
                folders = folders,
                lists = lists,
            ),
        )
    }

    suspend operator fun invoke(includeSystem: Boolean = false): String = execute(includeSystem)
}
