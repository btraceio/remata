package com.reviewplugin.ui.inlay

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.reviewplugin.anchor.AnchorResolver
import com.reviewplugin.anchor.AnchorResult
import com.reviewplugin.model.CommentStatus
import com.reviewplugin.model.ReviewComment
import com.reviewplugin.store.CommentStore
import java.util.concurrent.ConcurrentHashMap

class GhostHunkInlayManager(private val project: Project) : Disposable {

    private val inlays = ConcurrentHashMap<String, Inlay<*>>()
    private val listenerDisposable: Disposable

    companion object {
        const val GHOST_HUNK_PRIORITY = 100
    }

    init {
        val store = project.getService(CommentStore::class.java)
        listenerDisposable = store.addChangeListener { refreshAllEditors() }
        Disposer.register(this, listenerDisposable)
    }

    fun refreshAllEditors() {
        val editorManager = FileEditorManager.getInstance(project)
        for (editor in editorManager.allEditors) {
            if (editor is TextEditor) {
                refreshEditor(editor.editor)
            }
        }
    }

    fun refreshEditor(editor: Editor) {
        if (editor.isDisposed) return
        val document = editor.document
        val virtualFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getFile(document) ?: return

        val basePath = project.basePath ?: return
        val filePath = virtualFile.path
        if (!filePath.startsWith(basePath)) return
        val relativePath = filePath.removePrefix(basePath).removePrefix("/").replace('\\', '/')

        val store = project.getService(CommentStore::class.java)
        val comments = store.commentsForFile(relativePath)
        val resolver = AnchorResolver(project)
        val fileLines = document.text.lines()
        val modStamp = document.modificationStamp

        // Remove old inlays for this file
        val toRemove = inlays.entries.filter { (id, inlay) ->
            !inlay.isValid || comments.none { it.id == id }
        }
        for ((id, inlay) in toRemove) {
            Disposer.dispose(inlay)
            inlays.remove(id)
        }

        // Add/update inlays for resolved comments
        for (comment in comments) {
            if (!shouldShowGhostHunk(comment, fileLines, resolver, modStamp)) {
                inlays.remove(comment.id)?.let { if (it.isValid) Disposer.dispose(it) }
                continue
            }

            val result = resolver.resolve(comment, fileLines, modStamp)
            if (result !is AnchorResult.Found) continue

            // Remove existing inlay if present
            inlays.remove(comment.id)?.let { if (it.isValid) Disposer.dispose(it) }

            val offset = document.getLineStartOffset(result.line)
            val inlay = editor.inlayModel.addBlockElement(
                offset,
                true,  // relatesToPrecedingText
                true,  // showAbove
                GHOST_HUNK_PRIORITY,
                GhostHunkRenderer(comment)
            )
            if (inlay != null) {
                inlays[comment.id] = inlay
            }
        }
    }

    private fun shouldShowGhostHunk(
        comment: ReviewComment,
        currentLines: List<String>,
        resolver: AnchorResolver,
        modStamp: Long
    ): Boolean {
        val status = comment.effectiveStatus()
        if (status != CommentStatus.resolved) return false

        // Show if resolved_anchor exists (agent changed the code)
        if (comment.resolved_anchor != null) return true

        // Show if the target lines at current position differ from anchor
        val result = resolver.resolve(comment, currentLines, modStamp)
        if (result !is AnchorResult.Found) return false

        val target = comment.anchor.hunk.target
        val startLine = result.line
        if (startLine + target.size > currentLines.size) return true

        return target.indices.any { i ->
            currentLines[startLine + i].trim() != target[i].trim()
        }
    }

    override fun dispose() {
        for ((_, inlay) in inlays) {
            if (inlay.isValid) {
                Disposer.dispose(inlay)
            }
        }
        inlays.clear()
    }
}
