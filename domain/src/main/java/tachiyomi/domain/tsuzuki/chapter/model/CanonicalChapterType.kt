package tachiyomi.domain.tsuzuki.chapter.model

/** The semantic kind of a canonical chapter. */
enum class CanonicalChapterType {
    REGULAR,
    PROLOGUE,
    EPILOGUE,
    EXTRA,
    SPECIAL,
    ONESHOT,
    UNKNOWN,
    ;

    /** Stable display/order rank; this is deliberately not the enum ordinal. */
    val sortRank: Int
        get() = when (this) {
            PROLOGUE -> 0
            REGULAR -> 1
            EXTRA -> 2
            SPECIAL -> 3
            ONESHOT -> 4
            EPILOGUE -> 5
            UNKNOWN -> 9
        }
}
