package app.cash.quickjs

class QuickJsException : RuntimeException {

    constructor(message: String) : super(message)

    constructor(message: String, jsStackTrace: String) : super(message) {
        val frames = jsStackTrace.lineSequence()
            .mapNotNull(::parseFrame)
            .toList()
        if (frames.isNotEmpty()) {
            stackTrace = stackTrace + frames
        }
    }

    private companion object {
        private val frameRegex = Regex("""^\s*at\s+(?:([^\s(]+)\s+)?\((.*)\)$""")

        private fun parseFrame(line: String): StackTraceElement? {
            val match = frameRegex.matchEntire(line) ?: return null
            val location = match.groupValues[2]
            return StackTraceElement(
                match.groupValues[1].ifBlank { null },
                null,
                location.substringBefore(':').ifBlank { "<eval>" },
                location.substringAfter(':', "").toIntOrNull()?.takeIf { it > 0 } ?: -1,
            )
        }
    }
}
