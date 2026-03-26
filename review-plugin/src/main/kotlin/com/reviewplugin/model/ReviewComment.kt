package com.reviewplugin.model

import kotlinx.serialization.Serializable

@Serializable
enum class CommentStatus {
    open,
    resolved,
    wontfix
}

@Serializable
data class Hunk(
    val context_before: List<String>,
    val target: List<String>,
    val context_after: List<String>
)

@Serializable
data class Anchor(
    val file: String,
    val commit: String,
    val line_hint: Int,
    val hunk: Hunk
)

@Serializable
data class ThreadEntry(
    val id: String,
    val author: String,
    val created: String,
    val body: String,
    val status: CommentStatus? = null
)

@Serializable
data class ReviewComment(
    val id: String,
    val schema_version: Int = 1,
    val author: String,
    val created: String,
    val status: CommentStatus,
    val anchor: Anchor,
    val resolved_anchor: Anchor? = null,
    val body: String,
    val thread: List<ThreadEntry> = emptyList()
) {
    fun effectiveStatus(): CommentStatus {
        val lastThreadStatus = thread.lastOrNull { it.status != null }?.status
        return lastThreadStatus ?: status
    }
}
