package com.reviewplugin.anchor

import com.intellij.openapi.project.Project
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
        HunkMatcher.exactMatch(fileLines, anchor.line_hint, target)?.let {
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
        HunkMatcher.fuzzySearch(fileLines, anchor.hunk)?.let {
            return AnchorResult.Found(it)
        }

        return AnchorResult.Drifted
    }

    private fun gitDiffRemap(commit: String, file: String, hintLine: Int): Int? {
        val gitRunner = GitRunner(project.basePath ?: return null)
        val diffOutput = gitRunner.diffUnified(commit, file)
        if (diffOutput.isBlank()) {
            return hintLine - 1
        }
        return HunkMatcher.parseDiffAndRemap(diffOutput, hintLine)
    }
}
