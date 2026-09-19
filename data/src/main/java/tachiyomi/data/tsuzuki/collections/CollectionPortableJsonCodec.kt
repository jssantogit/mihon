package tachiyomi.data.tsuzuki.collections

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection
import tachiyomi.domain.tsuzuki.collections.portable.CURRENT_PORTABLE_COLLECTIONS_SCHEMA_VERSION
import tachiyomi.domain.tsuzuki.collections.portable.CollectionPortableCodec
import tachiyomi.domain.tsuzuki.collections.portable.PortableCollectionsDocument

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CollectionPortableJsonCodec : CollectionPortableCodec {

    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        prettyPrint = true
    }

    override fun encode(document: PortableCollectionsDocument): String {
        require(document.schemaVersion == CURRENT_PORTABLE_COLLECTIONS_SCHEMA_VERSION) {
            "Unsupported portable Collections schemaVersion: ${document.schemaVersion}"
        }

        val collections = document.collections
            .sortedWith(compareBy<TsuzukiCollection> { it.sortOrder }.thenBy { it.id })
        val folders = document.folders
            .sortedWith(
                compareBy<CollectionFolder> { it.collectionId }
                    .thenBy { it.sortOrder }
                    .thenBy { it.id },
            )
        val lists = document.lists
            .sortedWith(
                compareBy<CollectionList> { it.collectionId }
                    .thenBy { it.folderId }
                    .thenBy { it.sortOrder }
                    .thenBy { it.id },
            )

        val root = buildJsonObject {
            put("schemaVersion", document.schemaVersion)
            put(
                "collections",
                buildJsonArray {
                    collections.forEach { add(encodeCollection(it)) }
                },
            )
            put(
                "folders",
                buildJsonArray {
                    folders.forEach { add(encodeFolder(it)) }
                },
            )
            put(
                "lists",
                buildJsonArray {
                    lists.forEach { add(encodeList(it)) }
                },
            )
        }

        return json.encodeToString(JsonObject.serializer(), root)
    }

    override fun decode(encoded: String): PortableCollectionsDocument {
        val root = json.parseToJsonElement(encoded).jsonObject
        val schemaVersion = root.required("schemaVersion").jsonPrimitive.int
        require(schemaVersion == CURRENT_PORTABLE_COLLECTIONS_SCHEMA_VERSION) {
            "Unsupported portable Collections schemaVersion: $schemaVersion"
        }

        return PortableCollectionsDocument(
            schemaVersion = schemaVersion,
            collections = root.required("collections").jsonArray.map { decodeCollection(it.jsonObject) },
            folders = root.required("folders").jsonArray.map { decodeFolder(it.jsonObject) },
            lists = root.required("lists").jsonArray.map { decodeList(it.jsonObject) },
        )
    }

    private fun encodeCollection(collection: TsuzukiCollection): JsonObject = buildJsonObject {
        put("id", collection.id)
        put("title", collection.title)
        put("origin", collection.origin.name)
        put("sortOrder", collection.sortOrder)
        put("schemaVersion", collection.schemaVersion)
        put("revision", collection.revision)
        put("createdAt", collection.createdAt)
        put("updatedAt", collection.updatedAt)
        collection.deletedAt?.let { put("deletedAt", it) }
    }

    private fun decodeCollection(jsonObject: JsonObject): TsuzukiCollection {
        return TsuzukiCollection(
            id = jsonObject.string("id"),
            title = jsonObject.string("title"),
            origin = CollectionOrigin.valueOf(jsonObject.string("origin")),
            sortOrder = jsonObject.long("sortOrder"),
            schemaVersion = jsonObject.int("schemaVersion"),
            revision = jsonObject.long("revision"),
            createdAt = jsonObject.long("createdAt"),
            updatedAt = jsonObject.long("updatedAt"),
            deletedAt = jsonObject.longOrNull("deletedAt"),
        )
    }

    private fun encodeFolder(folder: CollectionFolder): JsonObject = buildJsonObject {
        put("id", folder.id)
        put("collectionId", folder.collectionId)
        folder.parentFolderId?.let { put("parentFolderId", it) }
        put("title", folder.title)
        put("origin", folder.origin.name)
        put("sortOrder", folder.sortOrder)
        put("schemaVersion", folder.schemaVersion)
        put("revision", folder.revision)
        put("createdAt", folder.createdAt)
        put("updatedAt", folder.updatedAt)
        folder.deletedAt?.let { put("deletedAt", it) }
    }

    private fun decodeFolder(jsonObject: JsonObject): CollectionFolder {
        return CollectionFolder(
            id = jsonObject.string("id"),
            collectionId = jsonObject.string("collectionId"),
            parentFolderId = jsonObject.stringOrNull("parentFolderId"),
            title = jsonObject.string("title"),
            origin = CollectionOrigin.valueOf(jsonObject.string("origin")),
            sortOrder = jsonObject.long("sortOrder"),
            schemaVersion = jsonObject.int("schemaVersion"),
            revision = jsonObject.long("revision"),
            createdAt = jsonObject.long("createdAt"),
            updatedAt = jsonObject.long("updatedAt"),
            deletedAt = jsonObject.longOrNull("deletedAt"),
        )
    }

    private fun encodeList(list: CollectionList): JsonObject = buildJsonObject {
        put("id", list.id)
        put("collectionId", list.collectionId)
        put("folderId", list.folderId)
        put("title", list.title)
        put("providerId", list.providerId)
        list.query?.let { query ->
            put(
                "query",
                json.parseToJsonElement(CollectionQueryJsonCodec.encode(query)),
            )
        }
        put("sort", list.sort.name)
        list.layoutType?.let { put("layoutType", it) }
        put("sortOrder", list.sortOrder)
        put("enabled", list.enabled)
        put("origin", list.origin.name)
        put("schemaVersion", list.schemaVersion)
        put("revision", list.revision)
        put("createdAt", list.createdAt)
        put("updatedAt", list.updatedAt)
        list.deletedAt?.let { put("deletedAt", it) }
    }

    private fun decodeList(jsonObject: JsonObject): CollectionList {
        return CollectionList(
            id = jsonObject.string("id"),
            collectionId = jsonObject.string("collectionId"),
            folderId = jsonObject.string("folderId"),
            title = jsonObject.string("title"),
            providerId = jsonObject.string("providerId"),
            query = jsonObject["query"]?.let(::decodeQuery),
            sort = CatalogSort.valueOf(jsonObject.string("sort")),
            layoutType = jsonObject.stringOrNull("layoutType"),
            sortOrder = jsonObject.long("sortOrder"),
            enabled = jsonObject.required("enabled").jsonPrimitive.content.toBooleanStrict(),
            origin = CollectionOrigin.valueOf(jsonObject.string("origin")),
            schemaVersion = jsonObject.int("schemaVersion"),
            revision = jsonObject.long("revision"),
            createdAt = jsonObject.long("createdAt"),
            updatedAt = jsonObject.long("updatedAt"),
            deletedAt = jsonObject.longOrNull("deletedAt"),
        )
    }

    private fun decodeQuery(element: JsonElement) =
        CollectionQueryJsonCodec.decode(
            json.encodeToString(JsonElement.serializer(), element),
        )

    private fun JsonObject.required(name: String): JsonElement = requireNotNull(this[name]) {
        "Missing required portable Collections JSON field: $name"
    }

    private fun JsonObject.string(name: String): String = required(name).jsonPrimitive.content

    private fun JsonObject.stringOrNull(name: String): String? = this[name]?.jsonPrimitive?.content

    private fun JsonObject.int(name: String): Int = required(name).jsonPrimitive.int

    private fun JsonObject.long(name: String): Long = required(name).jsonPrimitive.long

    private fun JsonObject.longOrNull(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
}
