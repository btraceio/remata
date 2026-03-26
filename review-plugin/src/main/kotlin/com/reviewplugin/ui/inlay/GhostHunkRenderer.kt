package com.reviewplugin.ui.inlay

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.reviewplugin.model.CommentStatus
import com.reviewplugin.model.ReviewComment
import java.awt.*

class GhostHunkRenderer(private val comment: ReviewComment) : EditorCustomElementRenderer {

    companion object {
        private val SEPARATOR_COLOR = JBColor(Color(0xD0, 0xD0, 0xD0), Color(0x50, 0x50, 0x50))
        private val GHOST_TEXT_COLOR = JBColor(Color(0x99, 0x99, 0x99), Color(0x77, 0x77, 0x77))
        private val OPEN_STRIPE_COLOR = JBColor(Color(0xE0, 0x60, 0x60), Color(0xCC, 0x44, 0x44))
        private val RESOLVED_STRIPE_COLOR = JBColor(Color(0x60, 0xA0, 0x60), Color(0x44, 0x88, 0x44))
        private val WONTFIX_STRIPE_COLOR = JBColor(Color(0xA0, 0xA0, 0xA0), Color(0x66, 0x66, 0x66))
        private val BACKGROUND_COLOR = JBColor(Color(0xF8, 0xF8, 0xF0), Color(0x30, 0x30, 0x28))
        private const val STRIPE_WIDTH = 3
        private const val LEFT_PADDING = 8
        private const val TOP_BOTTOM_PADDING = 2
    }

    private fun getHunkLines(): List<String> {
        val anchor = comment.anchor
        return anchor.hunk.target
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val fm = getFontMetrics(editor) ?: return 400
        val lines = getHunkLines()
        val maxLine = lines.maxByOrNull { it.length } ?: return 400
        return STRIPE_WIDTH + LEFT_PADDING + fm.stringWidth(maxLine) + LEFT_PADDING
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val lineHeight = editor.lineHeight
        val lines = getHunkLines()
        // +2 for separator lines, +1 for author/body summary line
        return lineHeight * (lines.size + 3) + TOP_BOTTOM_PADDING * 2
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        if (editor.isDisposed) return

        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val fm = getFontMetrics(editor) ?: return
        val lineHeight = editor.lineHeight
        val lines = getHunkLines()
        val x = targetRegion.x
        val y = targetRegion.y

        // Background
        g2.color = BACKGROUND_COLOR
        g2.fillRect(x, y, targetRegion.width, targetRegion.height)

        // Top separator
        g2.color = SEPARATOR_COLOR
        val dashStroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 4f), 0f)
        g2.stroke = dashStroke
        g2.drawLine(x, y + TOP_BOTTOM_PADDING, x + targetRegion.width, y + TOP_BOTTOM_PADDING)

        // Stripe color
        val stripeColor = when (comment.effectiveStatus()) {
            CommentStatus.open -> OPEN_STRIPE_COLOR
            CommentStatus.resolved -> RESOLVED_STRIPE_COLOR
            CommentStatus.wontfix -> WONTFIX_STRIPE_COLOR
        }

        // Hunk lines
        g2.font = getEditorFont(editor)
        val textY = y + TOP_BOTTOM_PADDING + lineHeight
        for ((i, line) in lines.withIndex()) {
            val lineY = textY + i * lineHeight

            // Left stripe
            g2.color = stripeColor
            g2.fillRect(x, lineY - lineHeight + fm.descent, STRIPE_WIDTH, lineHeight)

            // Ghost text
            g2.color = GHOST_TEXT_COLOR
            g2.drawString(line, x + STRIPE_WIDTH + LEFT_PADDING, lineY)
        }

        // Author + body summary
        val summaryY = textY + lines.size * lineHeight
        val italicFont = getEditorFont(editor).deriveFont(Font.ITALIC, getEditorFont(editor).size2D * 0.9f)
        g2.font = italicFont
        g2.color = GHOST_TEXT_COLOR
        val bodyPreview = if (comment.body.length > 60) comment.body.take(60) + "..." else comment.body
        g2.drawString("${comment.author}: $bodyPreview", x + STRIPE_WIDTH + LEFT_PADDING, summaryY)

        // Bottom separator
        g2.color = SEPARATOR_COLOR
        g2.stroke = dashStroke
        val bottomY = y + targetRegion.height - TOP_BOTTOM_PADDING
        g2.drawLine(x, bottomY, x + targetRegion.width, bottomY)

        g2.dispose()
    }

    private fun getFontMetrics(editor: Editor): FontMetrics? {
        val font = getEditorFont(editor)
        return editor.contentComponent.getFontMetrics(font)
    }

    private fun getEditorFont(editor: Editor): Font {
        val scheme = EditorColorsManager.getInstance().globalScheme
        return scheme.getFont(EditorFontType.PLAIN)
    }
}
