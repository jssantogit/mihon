package tachiyomi.domain.tsuzuki.sync.service

import tachiyomi.domain.tsuzuki.sync.model.SyncRevision

interface SyncRevisionSource {
    val deviceId: String

    fun nextRevision(): SyncRevision
}

interface SyncClock {
    fun nowEpochMillis(): Long
}
