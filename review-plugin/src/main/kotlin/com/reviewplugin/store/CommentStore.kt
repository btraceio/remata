package com.reviewplugin.store

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.reviewplugin.model.CommentStatus
import com.reviewplugin.model.ReviewComment
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class CommentStore(private val project: Project) : Disposable {

    private val comments = ConcurrentHashMap<String, ReviewComment>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val writeMutex = Mutex()
    private val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun allComments(): List<ReviewComment> = comments.values.toList()

    fun commentsForFile(projectRelativePath: String): List<ReviewComment> {
        val normalized = projectRelativePath.replace('\\', '/')
        return comments.values.filter { it.anchor.file.replace('\\', '/') == normalized }
    }

    fun openComments(): List<ReviewComment> =
        comments.values.filter { it.effectiveStatus() == CommentStatus.open }

    fun resolvedComments(): List<ReviewComment> =
        comments.values.filter { it.effectiveStatus() == CommentStatus.resolved }

    fun getComment(id: String): ReviewComment? = comments[id]

    fun write(comment: ReviewComment) {
        writeScope.launch {
            writeMutex.withLock {
                val dir = reviewCommentsDir()
                dir.mkdirs()
                val tmpFile = File(dir, "${comment.id}.json.tmp")
                val finalFile = File(dir, "${comment.id}.json")
                tmpFile.writeText(json.encodeToString(ReviewComment.serializer(), comment))
                tmpFile.renameTo(finalFile)
            }
            comments[comment.id] = comment
            fireChanged()
        }
    }

    fun writeSync(comment: ReviewComment) {
        val dir = reviewCommentsDir()
        dir.mkdirs()
        val tmpFile = File(dir, "${comment.id}.json.tmp")
        val finalFile = File(dir, "${comment.id}.json")
        tmpFile.writeText(json.encodeToString(ReviewComment.serializer(), comment))
        tmpFile.renameTo(finalFile)
        comments[comment.id] = comment
        fireChanged()
    }

    fun delete(id: String) {
        writeScope.launch {
            writeMutex.withLock {
                val file = File(reviewCommentsDir(), "$id.json")
                file.delete()
            }
            comments.remove(id)
            fireChanged()
        }
    }

    fun addChangeListener(listener: () -> Unit): Disposable {
        listeners.add(listener)
        return Disposable { listeners.remove(listener) }
    }

    internal fun reload(file: VirtualFile) {
        try {
            val content = String(file.contentsToByteArray(), Charsets.UTF_8)
            val comment = json.decodeFromString(ReviewComment.serializer(), content)
            if (comment.schema_version != 1) return
            comments[comment.id] = comment
            fireChanged()
        } catch (_: Exception) {
            // Skip malformed files
        }
    }

    internal fun reload(file: File) {
        try {
            val content = file.readText(Charsets.UTF_8)
            val comment = json.decodeFromString(ReviewComment.serializer(), content)
            if (comment.schema_version != 1) return
            comments[comment.id] = comment
            fireChanged()
        } catch (_: Exception) {
            // Skip malformed files
        }
    }

    internal fun remove(file: VirtualFile) {
        val id = file.nameWithoutExtension
        comments.remove(id)
        fireChanged()
    }

    fun loadAll() {
        val dir = reviewCommentsDir()
        if (!dir.exists()) return
        dir.listFiles()?.filter { it.extension == "json" }?.forEach { reload(it) }
    }

    fun reviewCommentsDir(): File {
        val basePath = project.basePath ?: throw IllegalStateException("Project has no base path")
        return File(basePath, ".review/comments")
    }

    private fun fireChanged() {
        listeners.forEach {
            try {
                it()
            } catch (_: Exception) {
            }
        }
    }

    override fun dispose() {
        writeScope.cancel()
        listeners.clear()
        comments.clear()
    }
}
