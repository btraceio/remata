package com.reviewplugin.anchor

import com.intellij.openapi.project.Project
import com.reviewplugin.model.Hunk
import com.reviewplugin.model.ReviewComment
import java.util.concurrent.ConcurrentHashMap

sealed class AnchorResult {
    /** Line is 0-based for IntelliJ APIs */
    data class Found(val line: Int) : AnchorResult()
    object Drifted : AnchorResult()
}

data class CacheKey(val commentId: String, val fileModStamp: Long)

class AnchorResolver(private val project: Project) {

    private val cache = ConcurrentHashMap<CacheKey, AnchorResult>()

    fun invalidateCache() {
        cache.clear()
    }

    fun resolve(comment: ReviewComment, fileLines: List<String>, fileModStamp: Long): AnchorResult {
        val key = CacheKey(comment.id, fileModStamp)
        return cache.getOrPut(key) {
            doResolve(comment, fileLines)
        }
    }

    private fun doResolve(comment: ReviewComment, fileLines: List<String>): AnchorResult {
        val anchor = comment.resolved_anchor ?: comment.anchor
        val target = anchor.hunk.target

        // Step 1: Exact match at hint
        exactMatch(fileLines, anchor.line_hint, target)?.let {
            return AnchorResult.Found(it)
        }

        // Step 2: Git diff remap
        try {
            gitDiffRemap(anchor.commit, anchor.file, anchor.line_hint)?.let {
                return AnchorResult.Found(it)
            }
        } catch (_: Exception) {
            // git might not be available or commit might not exist
        }

        // Step 3: Fuzzy search
        fuzzySearch(fileLines, anchor.hunk)?.let {
            return AnchorResult.Found(it)
        }

        return AnchorResult.Drifted
    }

    /**
     * Step 1: Check if lines at line_hint match the target exactly (trimmed comparison).
     * Returns 0-based line index or null.
     */
    internal fun exactMatch(lines: List<String>, lineHint: Int, target: List<String>): Int? {
        // line_hint is 1-based in the schema
        val startIdx = lineHint - 1
        if (startIdx < 0 || startIdx + target.size > lines.size) return null
        val matches = target.indices.all { i ->
            lines[startIdx + i].trim() == target[i].trim()
        }
        return if (matches) startIdx else null
    }

    /**
     * Step 2: Parse unified diff to remap line_hint from old commit to current.
     * Returns 0-based line index or null.
     */
    internal fun gitDiffRemap(commit: String, file: String, hintLine: Int): Int? {
        val gitRunner = GitRunner(project.basePath ?: return null)
        val diffOutput = gitRunner.diffUnified(commit, file)
        if (diffOutput.isBlank()) {
            // No diff means file hasn't changed, return original hint (0-based)
            return hintLine - 1
        }
        return parseDiffAndRemap(diffOutput, hintLine)
    }

    /**
     * Parse unified diff output and remap a 1-based old line number to a 0-based new line number.
     */
    internal fun parseDiffAndRemap(diffOutput: String, oldLine: Int): Int? {
        val hunkHeaderRegex = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")
        var offset = 0
        var lastOldEnd = 0

        for (line in diffOutput.lines()) {
            val match = hunkHeaderRegex.find(line) ?: continue
            val oldStart = match.groupValues[1].toInt()
            val oldCount = match.groupValues[2].ifEmpty { "1" }.toInt()
            val newStart = match.groupValues[3].toInt()
            val newCount = match.groupValues[4].ifEmpty { "1" }.toInt()

            // If our line is before this hunk, the accumulated offset applies
            if (oldLine < oldStart) {
                return oldLine - 1 + offset
            }

            // If our line falls within a deleted range in this hunk, it's gone
            val oldEnd = oldStart + oldCount - 1
            if (oldLine in oldStart..oldEnd) {
                // Line is in the modified hunk - could be deleted or changed
                // We can't precisely determine without parsing line-by-line, so return null
                // to fall through to fuzzy search
                return null
            }

            offset = (newStart + newCount) - (oldStart + oldCount)
            lastOldEnd = oldEnd
        }

        // Line is after all hunks
        return oldLine - 1 + offset
    }

    /**
     * Step 3: Sliding window fuzzy search using LCS similarity.
     * Returns 0-based line index or null.
     */
    internal fun fuzzySearch(lines: List<String>, hunk: Hunk): Int? {
        val searchBlock = hunk.context_before + hunk.target + hunk.context_after
        if (searchBlock.isEmpty()) return null

        val windowSize = searchBlock.size
        if (lines.size < windowSize) return null

        var bestScore = 0.0
        var bestLine = -1
        val threshold = 0.75

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
            // Return the line corresponding to the start of the target within the block
            return bestLine + hunk.context_before.size
        }
        return null
    }

    /**
     * Compute LCS-based similarity between two lists of strings.
     */
    private fun lcsScore(a: List<String>, b: List<String>): Double {
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
