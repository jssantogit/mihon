package eu.kanade.tachiyomi.data.collections.query

/**
 * Strongly typed, provider-neutral representation of queryable fields.
 * Extensible for generic catalog and library domains without encoding provider-specific identifiers.
 */
sealed interface QueryField : Comparable<QueryField> {
    val identifier: String

    override fun compareTo(other: QueryField): Int = identifier.compareTo(other.identifier)

    enum class Standard(override val identifier: String) : QueryField {
        WORK_TYPE("work_type"),
        STATUS("status"),
        START_YEAR("start_year"),
        RELEASE_YEAR("release_year"),
        START_DATE("start_date"),
        END_DATE("end_date"),
        CHAPTER_COUNT("chapter_count"),
        VOLUME_COUNT("volume_count"),
        GENRE("genre"),
        TAG("tag"),
        CATEGORY("category"),
        DEMOGRAPHIC("demographic"),
        COUNTRY("country"),
        AUTHOR("author"),
        ARTIST("artist"),
        PUBLISHER("publisher"),
        MAGAZINE("magazine"),
        SCORE("score"),
        RATING("rating"),
        POPULARITY("popularity"),
        FAVORITES("favorites"),
        RANK("rank"),
        IN_LIBRARY("in_library"),
        UNREAD_CHAPTERS("unread_chapters"),
        HAS_NEW_CHAPTERS("has_new_chapters"),
        READ_STATUS("read_status"),
        HAS_READING_SOURCE("has_reading_source"),
    }

    data class Custom(override val identifier: String) : QueryField {
        init {
            require(identifier.isNotBlank()) { "QueryField identifier cannot be blank" }
        }
    }

    companion object {
        val WORK_TYPE: QueryField = Standard.WORK_TYPE
        val STATUS: QueryField = Standard.STATUS
        val START_YEAR: QueryField = Standard.START_YEAR
        val RELEASE_YEAR: QueryField = Standard.RELEASE_YEAR
        val START_DATE: QueryField = Standard.START_DATE
        val END_DATE: QueryField = Standard.END_DATE
        val CHAPTER_COUNT: QueryField = Standard.CHAPTER_COUNT
        val VOLUME_COUNT: QueryField = Standard.VOLUME_COUNT
        val GENRE: QueryField = Standard.GENRE
        val TAG: QueryField = Standard.TAG
        val CATEGORY: QueryField = Standard.CATEGORY
        val DEMOGRAPHIC: QueryField = Standard.DEMOGRAPHIC
        val COUNTRY: QueryField = Standard.COUNTRY
        val AUTHOR: QueryField = Standard.AUTHOR
        val ARTIST: QueryField = Standard.ARTIST
        val PUBLISHER: QueryField = Standard.PUBLISHER
        val MAGAZINE: QueryField = Standard.MAGAZINE
        val SCORE: QueryField = Standard.SCORE
        val RATING: QueryField = Standard.RATING
        val POPULARITY: QueryField = Standard.POPULARITY
        val FAVORITES: QueryField = Standard.FAVORITES
        val RANK: QueryField = Standard.RANK
        val IN_LIBRARY: QueryField = Standard.IN_LIBRARY
        val UNREAD_CHAPTERS: QueryField = Standard.UNREAD_CHAPTERS
        val HAS_NEW_CHAPTERS: QueryField = Standard.HAS_NEW_CHAPTERS
        val READ_STATUS: QueryField = Standard.READ_STATUS
        val HAS_READING_SOURCE: QueryField = Standard.HAS_READING_SOURCE

        fun fromString(identifier: String): QueryField {
            return Standard.entries.find { it.identifier.equals(identifier, ignoreCase = true) }
                ?: Custom(identifier)
        }
    }
}
