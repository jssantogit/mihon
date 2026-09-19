package tachiyomi.domain.tsuzuki.source.interactor

import dev.zacsweers.metro.Inject
import java.text.Normalizer
import java.util.Locale

@Inject
class ScoreSourceTitleMatch {

    operator fun invoke(targetTitle: String, candidateTitle: String): Double {
        val normTarget = normalize(targetTitle)
        val normCandidate = normalize(candidateTitle)

        if (normTarget.isEmpty() && normCandidate.isEmpty()) {
            return if (targetTitle.isBlank() && candidateTitle.isBlank()) 1.0 else 0.0
        }
        if (normTarget.isEmpty() || normCandidate.isEmpty()) {
            return 0.0
        }

        if (normTarget == normCandidate) {
            return 1.0
        }

        val tokensTarget = normTarget.split(" ").filter { it.isNotBlank() }
        val tokensCandidate = normCandidate.split(" ").filter { it.isNotBlank() }

        val totalTokens = tokensTarget.size + tokensCandidate.size
        val tokenScore = if (totalTokens == 0) {
            1.0
        } else {
            val mapTarget = tokensTarget.groupingBy { it }.eachCount()
            val mapCandidate = tokensCandidate.groupingBy { it }.eachCount()
            var common = 0
            for ((token, countTarget) in mapTarget) {
                val countCandidate = mapCandidate[token] ?: 0
                common += minOf(countTarget, countCandidate)
            }
            (2.0 * common) / totalTokens
        }

        val maxLen = maxOf(normTarget.length, normCandidate.length)
        val editScore = if (maxLen == 0) {
            1.0
        } else {
            val dist = levenshteinDistance(normTarget, normCandidate)
            1.0 - (dist.toDouble() / maxLen)
        }

        val combined = 0.5 * tokenScore + 0.5 * editScore
        return combined.coerceIn(0.0, 1.0)
    }

    private fun normalize(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        val withoutDiacritics = decomposed.replace("\\p{M}+".toRegex(), "")
        val lower = withoutDiacritics.lowercase(Locale.ROOT)
        val alphanumericOnly = lower.replace("[^a-z0-9\\s]".toRegex(), " ")
        return alphanumericOnly.replace("\\s+".toRegex(), " ").trim()
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        var prev = IntArray(s2.length + 1) { it }
        var curr = IntArray(s2.length + 1)

        for (i in s1.indices) {
            curr[0] = i + 1
            for (j in s2.indices) {
                val cost = if (s1[i] == s2[j]) 0 else 1
                curr[j + 1] = minOf(
                    curr[j] + 1,
                    prev[j + 1] + 1,
                    prev[j] + cost,
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[s2.length]
    }
}
