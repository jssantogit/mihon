package tachiyomi.domain.tsuzuki.reader.service

/**
 * Projects canonical Reader checkpoints into Mihon's local chapter/history rows.
 *
 * The caller must persist canonical state first. Mihon IDs are local operational
 * references only and must never become canonical or synchronizable identity.
 */
interface CanonicalReaderCompatibilityGateway {

    suspend fun projectProgress(
        mihonChapterId: Long,
        read: Boolean,
        lastPageRead: Long,
    )

    suspend fun projectHistory(
        mihonChapterId: Long,
        readAt: Long,
        sessionReadDuration: Long,
    )
}
