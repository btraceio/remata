package com.reviewplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextArea
import com.reviewplugin.anchor.GitRunner
import com.reviewplugin.model.*
import com.reviewplugin.store.CommentStore
import java.awt.BorderLayout
import java.awt.Dimension
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.border.EmptyBorder

class NewReviewCommentAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val document = editor.document

        val basePath = project.basePath ?: return
        val vFile = psiFile.virtualFile ?: return
        val filePath = vFile.path
        if (!filePath.startsWith(basePath)) return
        val relativePath = filePath.removePrefix(basePath).removePrefix("/").replace('\\', '/')

        // Determine selected lines
        val selectionModel = editor.selectionModel
        val startLine: Int
        val endLine: Int
        if (selectionModel.hasSelection()) {
            startLine = document.getLineNumber(selectionModel.selectionStart)
            endLine = document.getLineNumber(selectionModel.selectionEnd)
        } else {
            val caretLine = editor.caretModel.logicalPosition.line
            startLine = caretLine
            endLine = caretLine
        }

        val allLines = document.text.lines()

        // Extract target lines (1-based for the schema)
        val targetLines = (startLine..endLine).map { allLines.getOrElse(it) { "" } }

        // Extract context_before (2-3 lines before target)
        val contextBeforeStart = (startLine - 3).coerceAtLeast(0)
        val contextBefore = (contextBeforeStart until startLine).map { allLines.getOrElse(it) { "" } }

        // Extract context_after (2-3 lines after target)
        val contextAfterEnd = (endLine + 4).coerceAtMost(allLines.size)
        val contextAfter = ((endLine + 1) until contextAfterEnd).map { allLines.getOrElse(it) { "" } }

        // Get current commit
        val commit = try {
            GitRunner(basePath).currentCommit()
        } catch (_: Exception) {
            "unknown"
        }

        // Show dialog
        val dialog = CommentDialog(project)
        if (!dialog.showAndGet()) return

        val body = dialog.commentText.trim()
        if (body.isEmpty()) return

        val commentId = UUID.randomUUID().toString().replace("-", "").take(8)
        val comment = ReviewComment(
            id = commentId,
            schema_version = 1,
            author = "human",
            created = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            status = CommentStatus.open,
            anchor = Anchor(
                file = relativePath,
                commit = commit,
                line_hint = startLine + 1, // 1-based
                hunk = Hunk(
                    context_before = contextBefore,
                    target = targetLines,
                    context_after = contextAfter
                )
            ),
            body = body
        )

        val store = project.getService(CommentStore::class.java)
        store.writeSync(comment)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && e.project != null
    }

    private class CommentDialog(project: Project) : DialogWrapper(project) {
        private val textArea = JBTextArea(8, 50)
        val commentText: String get() = textArea.text

        init {
            title = "Add Review Comment"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            panel.border = EmptyBorder(8, 8, 8, 8)
            textArea.lineWrap = true
            textArea.wrapStyleWord = true
            val scrollPane = JScrollPane(textArea)
            scrollPane.preferredSize = Dimension(400, 200)
            panel.add(scrollPane, BorderLayout.CENTER)
            return panel
        }

        override fun getPreferredFocusedComponent(): JComponent = textArea
    }
}
