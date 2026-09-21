package tachiyomi.domain.tsuzuki.addon

interface AddonRegistry {
    fun contentProviders(): List<ContentProvider>

    fun chapterProbeProviders(): List<ChapterProbeProvider>
}
