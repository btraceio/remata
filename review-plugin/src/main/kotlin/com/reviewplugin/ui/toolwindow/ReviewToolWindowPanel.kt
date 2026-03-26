package com.reviewplugin.ui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.reviewplugin.anchor.AnchorResolver
import com.reviewplugin.anchor.AnchorResult
import com.reviewplugin.model.CommentStatus
import com.reviewplugin.model.ReviewComment
import com.reviewplugin.model.ThreadEntry
import com.reviewplugin.store.CommentStore
import java.awt.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*
import javax.swing.*
import javax.swing.border.EmptyBorder

class ReviewToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val store = project.getService(CommentStore::class.java)
    private val listModel = DefaultListModel<ReviewComment>()
    private val commentList = JBList(listModel)
    private val detailPanel = JPanel(BorderLayout())
    private var currentFilter: CommentStatus? = null

    init {
        val toolbar = createToolbar()
        add(toolbar, BorderLayout.NORTH)

        val splitter = JBSplitter(false, 0.35f)

        commentList.cellRenderer = CommentListCellRenderer()
        commentList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selected = commentList.selectedValue
                if (selected != null) {
                    showCommentDetail(selected)
                }
            }
        }

        splitter.firstComponent = JBScrollPane(commentList)
        splitter.secondComponent = detailPanel

        add(splitter, BorderLayout.CENTER)

        store.addChangeListener {
            ApplicationManager.getApplication().invokeLater { reloadList() }
        }
        reloadList()
    }

    private fun createToolbar(): JPanel {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))

        val allBtn = JButton("All")
        allBtn.addActionListener { currentFilter = null; reloadList() }

        val openBtn = JButton("Open")
        openBtn.addActionListener { currentFilter = CommentStatus.open; reloadList() }

        val resolvedBtn = JButton("Resolved")
        resolvedBtn.addActionListener { currentFilter = CommentStatus.resolved; reloadList() }

        toolbar.add(allBtn)
        toolbar.add(openBtn)
        toolbar.add(resolvedBtn)

        return toolbar
    }

    fun selectComment(commentId: String) {
        for (i in 0 until listModel.size()) {
            if (listModel[i].id == commentId) {
                commentList.selectedIndex = i
                commentList.ensureIndexIsVisible(i)
                break
            }
        }
    }

    private fun reloadList() {
        val comments = when (currentFilter) {
            CommentStatus.open -> store.openComments()
            CommentStatus.resolved -> store.resolvedComments()
            CommentStatus.wontfix -> store.allComments().filter { it.effectiveStatus() == CommentStatus.wontfix }
            null -> store.allComments()
        }
        val selected = commentList.selectedValue?.id
        listModel.clear()
        comments.sortedByDescending { it.created }.forEach { listModel.addElement(it) }
        if (selected != null) {
            for (i in 0 until listModel.size()) {
                if (listModel[i].id == selected) {
                    commentList.selectedIndex = i
                    break
                }
            }
        }
    }

    private fun showCommentDetail(comment: ReviewComment) {
        detailPanel.removeAll()

        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = EmptyBorder(8, 8, 8, 8)

        // Header
        val header = JPanel(BorderLayout())
        header.maximumSize = Dimension(Int.MAX_VALUE, 40)
        val fileLabel = JLabel(comment.anchor.file)
        fileLabel.font = fileLabel.font.deriveFont(Font.BOLD)
        fileLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        fileLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                navigateToComment(comment)
            }
        })
        header.add(fileLabel, BorderLayout.WEST)
        val lineInfo = JLabel("Line ~${comment.anchor.line_hint} | ${comment.author} | ${formatDate(comment.created)}")
        header.add(lineInfo, BorderLayout.EAST)
        panel.add(header)
        panel.add(Box.createVerticalStrut(4))

        // Status badge
        val statusLabel = JLabel(comment.effectiveStatus().name.uppercase())
        statusLabel.foreground = when (comment.effectiveStatus()) {
            CommentStatus.open -> Color(0xE0, 0x60, 0x60)
            CommentStatus.resolved -> Color(0x60, 0xA0, 0x60)
            CommentStatus.wontfix -> Color(0xA0, 0xA0, 0xA0)
        }
        statusLabel.font = statusLabel.font.deriveFont(Font.BOLD, 11f)
        panel.add(statusLabel)
        panel.add(Box.createVerticalStrut(8))

        // Body
        val bodyArea = JBTextArea(comment.body)
        bodyArea.isEditable = false
        bodyArea.lineWrap = true
        bodyArea.wrapStyleWord = true
        bodyArea.border = EmptyBorder(4, 4, 4, 4)
        bodyArea.background = UIManager.getColor("Panel.background")
        panel.add(bodyArea)
        panel.add(Box.createVerticalStrut(8))

        // Thread entries
        if (comment.thread.isNotEmpty()) {
            val separator = JSeparator()
            separator.maximumSize = Dimension(Int.MAX_VALUE, 2)
            panel.add(separator)
            panel.add(Box.createVerticalStrut(4))

            for (entry in comment.thread) {
                val entryPanel = JPanel(BorderLayout())
                entryPanel.border = EmptyBorder(4, 8, 4, 4)
                val entryHeader = JLabel("${entry.author} | ${formatDate(entry.created)}" +
                    (if (entry.status != null) " | ${entry.status.name}" else ""))
                entryHeader.font = entryHeader.font.deriveFont(Font.ITALIC, 11f)
                entryPanel.add(entryHeader, BorderLayout.NORTH)
                val entryBody = JBTextArea(entry.body)
                entryBody.isEditable = false
                entryBody.lineWrap = true
                entryBody.wrapStyleWord = true
                entryBody.background = UIManager.getColor("Panel.background")
                entryPanel.add(entryBody, BorderLayout.CENTER)
                panel.add(entryPanel)
                panel.add(Box.createVerticalStrut(4))
            }
        }

        panel.add(Box.createVerticalStrut(8))

        // Reply area
        val replyArea = JBTextArea(3, 40)
        replyArea.border = BorderFactory.createTitledBorder("Reply")
        replyArea.lineWrap = true
        replyArea.wrapStyleWord = true
        panel.add(replyArea)
        panel.add(Box.createVerticalStrut(4))

        // Action buttons
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val submitBtn = JButton("Reply")
        submitBtn.addActionListener {
            val text = replyArea.text.trim()
            if (text.isNotEmpty()) {
                val entry = ThreadEntry(
                    id = UUID.randomUUID().toString().take(8),
                    author = "human",
                    created = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    body = text
                )
                val updated = comment.copy(thread = comment.thread + entry)
                store.writeSync(updated)
                showCommentDetail(updated)
            }
        }

        val resolveBtn = JButton("Resolve")
        resolveBtn.addActionListener {
            val entry = ThreadEntry(
                id = UUID.randomUUID().toString().take(8),
                author = "human",
                created = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                body = replyArea.text.trim().ifEmpty { "Resolved." },
                status = CommentStatus.resolved
            )
            val updated = comment.copy(
                status = CommentStatus.resolved,
                thread = comment.thread + entry
            )
            store.writeSync(updated)
            reloadList()
        }

        val wontfixBtn = JButton("Won't Fix")
        wontfixBtn.addActionListener {
            val entry = ThreadEntry(
                id = UUID.randomUUID().toString().take(8),
                author = "human",
                created = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                body = replyArea.text.trim().ifEmpty { "Won't fix." },
                status = CommentStatus.wontfix
            )
            val updated = comment.copy(
                status = CommentStatus.wontfix,
                thread = comment.thread + entry
            )
            store.writeSync(updated)
            reloadList()
        }

        buttonPanel.add(submitBtn)
        buttonPanel.add(resolveBtn)
        buttonPanel.add(wontfixBtn)
        panel.add(buttonPanel)

        // Filler
        panel.add(Box.createVerticalGlue())

        detailPanel.add(JBScrollPane(panel), BorderLayout.CENTER)
        detailPanel.revalidate()
        detailPanel.repaint()
    }

    private fun navigateToComment(comment: ReviewComment) {
        val basePath = project.basePath ?: return
        val filePath = "$basePath/${comment.anchor.file}"
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return

        val resolver = AnchorResolver(project)
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vFile) ?: return
        val fileLines = document.text.lines()
        val result = resolver.resolve(comment, fileLines, document.modificationStamp)
        val line = when (result) {
            is AnchorResult.Found -> result.line
            else -> (comment.anchor.line_hint - 1).coerceAtLeast(0)
        }
        val descriptor = OpenFileDescriptor(project, vFile, line, 0)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }

    private fun formatDate(isoDate: String): String {
        return try {
            val instant = Instant.parse(isoDate)
            val dt = instant.atOffset(ZoneOffset.UTC)
            dt.format(DateTimeFormatter.ofPattern("MMM d HH:mm"))
        } catch (_: Exception) {
            isoDate
        }
    }

    private class CommentListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is ReviewComment) {
                val icon = when (value.effectiveStatus()) {
                    CommentStatus.open -> "\u25CF"    // filled circle
                    CommentStatus.resolved -> "\u2713" // check mark
                    CommentStatus.wontfix -> "\u2014"  // em dash
                }
                val bodyPreview = if (value.body.length > 30) value.body.take(30) + "..." else value.body
                text = "$icon ${value.effectiveStatus().name} | $bodyPreview"
            }
            return component
        }
    }
}
