package com.reviewplugin.anchor

import com.reviewplugin.model.Hunk

/**
 * Pure algorithms for hunk matching and diff remapping.
 * No IntelliJ dependencies — fully unit-testable.
 */
object HunkMatcher {

    /**
     * Check if lines at lineHint (1-based) match the target exactly (trimmed comparison).
     * Returns 0-based line index or null.
     */
    fun exactMatch(lines: List<String>, lineHint: Int, target: List<String>): Int? {
        val startIdx = lineHint - 1
        if (startIdx < 0 || startIdx + target.size > lines.size) return null
        val matches = target.indices.all { i ->
            lines[startIdx + i].trim() == target[i].trim()
        }
        return if (matches) startIdx else null
    }

    /**
     * Parse unified diff output and remap a 1-based old line number to a 0-based new line number.
     * Returns null if the line falls within a modified hunk (can't reliably remap).
     */
    fun parseDiffAndRemap(diffOutput: String, oldLine: Int): Int? {
        val hunkHeaderRegex = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")
        var offset = 0

        for (line in diffOutput.lines()) {
            val match = hunkHeaderRegex.find(line) ?: continue
            val oldStart = match.groupValues[1].toInt()
            val oldCount = match.groupValues[2].ifEmpty { "1" }.toInt()
            val newStart = match.groupValues[3].toInt()
            val newCount = match.groupValues[4].ifEmpty { "1" }.toInt()

            if (oldLine < oldStart) {
                return oldLine - 1 + offset
            }

            val oldEnd = oldStart + oldCount - 1
            if (oldLine in oldStart..oldEnd) {
                return null
            }

            offset = (newStart + newCount) - (oldStart + oldCount)
        }

        return oldLine - 1 + offset
    }

    /**
     * Sliding window fuzzy search using LCS similarity.
     * Returns 0-based line index of the target start, or null if no match above threshold.
     */
    fun fuzzySearch(lines: List<String>, hunk: Hunk, threshold: Double = 0.75): Int? {
        val searchBlock = hunk.context_before + hunk.target + hunk.context_after
        if (searchBlock.isEmpty()) return null

        val windowSize = searchBlock.size
        if (lines.size < windowSize) return null

        var bestScore = 0.0
        var bestLine = -1

        for (i in 0..lines.size - windowSize) {
            val window = lines.subList(i, i + windowSize)
            val score = lcsScore(
                window.map { it.trim() },
                searchBlock.map { it.trim() }
            )
            if (score > bestScore) {
                bestScore = score
                bestLine = i
            }
        }

        if (bestScore >= threshold && bestLine >= 0) {
            return bestLine + hunk.context_before.size
        }
        return null
    }

    /**
     * Compute LCS-based similarity between two lists of strings.
     * Returns a value between 0.0 and 1.0.
     */
    fun lcsScore(a: List<String>, b: List<String>): Double {
        val m = a.size
        val n = b.size
        if (m == 0 || n == 0) return 0.0

        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        return dp[m][n].toDouble() / maxOf(m, n)
    }
}
