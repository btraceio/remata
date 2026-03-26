package com.reviewplugin.ui.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.reviewplugin.anchor.AnchorResolver
import com.reviewplugin.anchor.AnchorResult
import com.reviewplugin.model.CommentStatus
import com.reviewplugin.model.ReviewComment
import com.reviewplugin.store.CommentStore
import javax.swing.Icon

class ReviewGutterIconProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only process leaf elements at column 0 to avoid duplicates
        if (element.children.isNotEmpty()) return null
        val containingFile = element.containingFile ?: return null
        val project = element.project
        val document = containingFile.viewProvider.document ?: return null
        val elementLine = document.getLineNumber(element.textOffset)

        // Only process the first element on each line
        val lineStartOffset = document.getLineStartOffset(elementLine)
        if (element.textOffset != lineStartOffset && element.textRange.startOffset > lineStartOffset) {
            // Check if there's a previous sibling on the same line
            val prevLeaf = element.prevSibling
            if (prevLeaf != null && document.getLineNumber(prevLeaf.textOffset) == elementLine) {
                return null
            }
        }

        val relativePath = getRelativePath(project, containingFile) ?: return null
        val store = project.getService(CommentStore::class.java)
        val comments = store.commentsForFile(relativePath)
        if (comments.isEmpty()) return null

        val resolver = AnchorResolver(project)
        val fileContent = document.text.lines()
        val modStamp = document.modificationStamp

        for (comment in comments) {
            val result = resolver.resolve(comment, fileContent, modStamp)
            if (result is AnchorResult.Found && result.line == elementLine) {
                return createMarker(element, comment)
            }
        }
        return null
    }

    private fun createMarker(element: PsiElement, comment: ReviewComment): LineMarkerInfo<PsiElement> {
        val icon = iconForStatus(comment.effectiveStatus())
        val tooltip = buildTooltip(comment)
        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { tooltip },
            { _, _ ->
                val toolWindow = ToolWindowManager.getInstance(element.project)
                    .getToolWindow("Code Review")
                toolWindow?.show()
            },
            GutterIconRenderer.Alignment.RIGHT,
            { tooltip }
        )
    }

    private fun iconForStatus(status: CommentStatus): Icon {
        return when (status) {
            CommentStatus.open -> AllIcons.General.BalloonError
            CommentStatus.resolved -> AllIcons.General.InspectionsOK
            CommentStatus.wontfix -> AllIcons.General.Note
        }
    }

    private fun buildTooltip(comment: ReviewComment): String {
        val body = comment.body
        val truncated = if (body.length > 80) body.take(80) + "..." else body
        return "[${comment.author}] $truncated"
    }

    private fun getRelativePath(project: Project, psiFile: PsiFile): String? {
        val basePath = project.basePath ?: return null
        val vFile: VirtualFile = psiFile.virtualFile ?: return null
        val filePath = vFile.path
        if (!filePath.startsWith(basePath)) return null
        return filePath.removePrefix(basePath).removePrefix("/").replace('\\', '/')
    }
}
