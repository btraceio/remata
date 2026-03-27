package com.reviewplugin.anchor

import com.reviewplugin.model.Hunk
import org.junit.Assert.*
import org.junit.Test

class HunkMatcherTest {

    // ── exactMatch ──────────────────────────────────────────────────

    @Test
    fun `exactMatch finds target at hint position`() {
        val lines = listOf("line 1", "line 2", "target A", "target B", "line 5")
        assertEquals(2, HunkMatcher.exactMatch(lines, 3, listOf("target A", "target B")))
    }

    @Test
    fun `exactMatch returns null when lines do not match`() {
        val lines = listOf("line 1", "line 2", "different", "line 4")
        assertNull(HunkMatcher.exactMatch(lines, 3, listOf("target A")))
    }

    @Test
    fun `exactMatch trims whitespace for comparison`() {
        val lines = listOf("  target line  ", "  other  ")
        assertEquals(0, HunkMatcher.exactMatch(lines, 1, listOf("target line")))
    }

    @Test
    fun `exactMatch returns null when hint is out of bounds`() {
        val lines = listOf("line 1")
        assertNull(HunkMatcher.exactMatch(lines, 1, listOf("line 1", "line 2")))
    }

    @Test
    fun `exactMatch with hint at line 1`() {
        val lines = listOf("first line", "second line")
        assertEquals(0, HunkMatcher.exactMatch(lines, 1, listOf("first line")))
    }

    @Test
    fun `exactMatch with hint at last line`() {
        val lines = listOf("first", "second", "third")
        assertEquals(2, HunkMatcher.exactMatch(lines, 3, listOf("third")))
    }

    @Test
    fun `exactMatch with negative hint returns null`() {
        val lines = listOf("line 1")
        assertNull(HunkMatcher.exactMatch(lines, -1, listOf("line 1")))
    }

    @Test
    fun `exactMatch with zero hint returns null`() {
        val lines = listOf("line 1")
        assertNull(HunkMatcher.exactMatch(lines, 0, listOf("line 1")))
    }

    @Test
    fun `exactMatch with empty target always matches`() {
        val lines = listOf("line 1")
        // Empty target list: all indices trivially match
        assertEquals(0, HunkMatcher.exactMatch(lines, 1, emptyList()))
    }

    @Test
    fun `exactMatch with empty file returns null for non-empty target`() {
        assertNull(HunkMatcher.exactMatch(emptyList(), 1, listOf("target")))
    }

    @Test
    fun `exactMatch with multi-line target that partially matches returns null`() {
        val lines = listOf("A", "B", "C", "D")
        // Only first line matches
        assertNull(HunkMatcher.exactMatch(lines, 1, listOf("A", "X")))
    }

    @Test
    fun `exactMatch hint beyond file length returns null`() {
        val lines = listOf("line 1", "line 2")
        assertNull(HunkMatcher.exactMatch(lines, 10, listOf("line 1")))
    }

    // ── parseDiffAndRemap ───────────────────────────────────────────

    @Test
    fun `parseDiffAndRemap with lines added at beginning`() {
        val diff = """
            diff --git a/test.java b/test.java
            index abc..def 100644
            --- a/test.java
            +++ b/test.java
            @@ -1,3 +1,5 @@
            +new line 1
            +new line 2
             existing line 1
             existing line 2
             existing line 3
        """.trimIndent()
        // Old line 5 → new line 7 → 0-based 6
        assertEquals(6, HunkMatcher.parseDiffAndRemap(diff, 5))
    }

    @Test
    fun `parseDiffAndRemap returns null for modified line in hunk`() {
        val diff = """
            diff --git a/test.java b/test.java
            @@ -3,2 +3,2 @@
            -old line 3
            +new line 3
             unchanged line 4
        """.trimIndent()
        assertNull(HunkMatcher.parseDiffAndRemap(diff, 3))
    }

    @Test
    fun `parseDiffAndRemap empty diff returns original line`() {
        // No hunk headers → line after all (zero) hunks
        assertEquals(9, HunkMatcher.parseDiffAndRemap("", 10))
    }

    @Test
    fun `parseDiffAndRemap line before any hunk`() {
        val diff = """
            @@ -5,3 +5,4 @@
            +added
             context
             context
             context
        """.trimIndent()
        // Old line 2 is before the hunk at line 5, offset is still 0
        assertEquals(1, HunkMatcher.parseDiffAndRemap(diff, 2))
    }

    @Test
    fun `parseDiffAndRemap line after hunk with deletions`() {
        val diff = """
            @@ -3,3 +3,1 @@
            -deleted 1
            -deleted 2
             kept
        """.trimIndent()
        // Hunk covers old lines 3-5 (3,3). new is 3,1. offset = (3+1) - (3+3) = -2
        // Old line 7 → 7 - 1 + (-2) = 4 (0-based)
        assertEquals(4, HunkMatcher.parseDiffAndRemap(diff, 7))
    }

    @Test
    fun `parseDiffAndRemap with multiple hunks`() {
        val diff = """
            @@ -2,2 +2,3 @@
            +added line
             existing
             existing
            @@ -10,2 +11,3 @@
            +another added
             more context
             more context
        """.trimIndent()
        // After first hunk: offset = (2+3)-(2+2) = 1
        // Old line 5 is between hunks → 5 - 1 + 1 = 5 (0-based)
        assertEquals(5, HunkMatcher.parseDiffAndRemap(diff, 5))
    }

    @Test
    fun `parseDiffAndRemap with single line hunk no count`() {
        // @@ -5 +5,2 @@ means old count=1, new count=2
        val diff = """
            @@ -5 +5,2 @@
            -old
            +new1
            +new2
        """.trimIndent()
        // Old line 5 is in the hunk (5..5)
        assertNull(HunkMatcher.parseDiffAndRemap(diff, 5))
        // Old line 6 is after: offset = (5+2)-(5+1) = 1, result = 6-1+1 = 6
        assertEquals(6, HunkMatcher.parseDiffAndRemap(diff, 6))
    }

    // ── fuzzySearch ─────────────────────────────────────────────────

    @Test
    fun `fuzzySearch finds shifted target`() {
        val lines = listOf(
            "new line 1", "new line 2",
            "context before 1", "context before 2",
            "target line A", "target line B",
            "context after 1"
        )
        val hunk = Hunk(
            context_before = listOf("context before 1", "context before 2"),
            target = listOf("target line A", "target line B"),
            context_after = listOf("context after 1")
        )
        assertEquals(4, HunkMatcher.fuzzySearch(lines, hunk))
    }

    @Test
    fun `fuzzySearch returns null when no match`() {
        val lines = listOf("completely", "different", "content", "here")
        val hunk = Hunk(
            context_before = listOf("context before"),
            target = listOf("target line"),
            context_after = listOf("context after")
        )
        assertNull(HunkMatcher.fuzzySearch(lines, hunk))
    }

    @Test
    fun `fuzzySearch with empty hunk returns null`() {
        val lines = listOf("any line")
        val hunk = Hunk(emptyList(), emptyList(), emptyList())
        assertNull(HunkMatcher.fuzzySearch(lines, hunk))
    }

    @Test
    fun `fuzzySearch with file smaller than window returns null`() {
        val lines = listOf("one line")
        val hunk = Hunk(
            context_before = listOf("before"),
            target = listOf("target"),
            context_after = listOf("after")
        )
        assertNull(HunkMatcher.fuzzySearch(lines, hunk))
    }

    @Test
    fun `fuzzySearch tolerates minor edits in context`() {
        val lines = listOf(
            "ctx before 1 modified",  // slightly different
            "ctx before 2",
            "target line X",
            "target line Y",
            "ctx after 1"
        )
        val hunk = Hunk(
            context_before = listOf("ctx before 1", "ctx before 2"),
            target = listOf("target line X", "target line Y"),
            context_after = listOf("ctx after 1")
        )
        // 4 out of 5 lines match exactly → score = 0.8, above 0.75 threshold
        assertEquals(2, HunkMatcher.fuzzySearch(lines, hunk))
    }

    @Test
    fun `fuzzySearch picks best match among multiple candidates`() {
        val lines = listOf(
            "context before",      // partial match
            "wrong target",
            "context after",
            "some filler",
            "context before",      // exact match
            "target line",
            "context after"
        )
        val hunk = Hunk(
            context_before = listOf("context before"),
            target = listOf("target line"),
            context_after = listOf("context after")
        )
        // Second occurrence (index 4-6) is the exact match
        assertEquals(5, HunkMatcher.fuzzySearch(lines, hunk))
    }

    @Test
    fun `fuzzySearch with only target lines no context`() {
        val lines = listOf("a", "b", "target1", "target2", "c")
        val hunk = Hunk(
            context_before = emptyList(),
            target = listOf("target1", "target2"),
            context_after = emptyList()
        )
        assertEquals(2, HunkMatcher.fuzzySearch(lines, hunk))
    }

    @Test
    fun `fuzzySearch with custom threshold`() {
        val lines = listOf("ctx", "target modified", "ctx after")
        val hunk = Hunk(
            context_before = listOf("ctx"),
            target = listOf("target original"),
            context_after = listOf("ctx after")
        )
        // With default threshold 0.75, 2/3 match = 0.67 → null
        assertNull(HunkMatcher.fuzzySearch(lines, hunk, 0.75))
        // With lowered threshold → finds it
        assertNotNull(HunkMatcher.fuzzySearch(lines, hunk, 0.5))
    }

    // ── lcsScore ────────────────────────────────────────────────────

    @Test
    fun `lcsScore identical lists returns 1`() {
        assertEquals(1.0, HunkMatcher.lcsScore(listOf("a", "b", "c"), listOf("a", "b", "c")), 0.001)
    }

    @Test
    fun `lcsScore completely different lists returns 0`() {
        assertEquals(0.0, HunkMatcher.lcsScore(listOf("a", "b"), listOf("x", "y")), 0.001)
    }

    @Test
    fun `lcsScore empty list returns 0`() {
        assertEquals(0.0, HunkMatcher.lcsScore(emptyList(), listOf("a")), 0.001)
        assertEquals(0.0, HunkMatcher.lcsScore(listOf("a"), emptyList()), 0.001)
    }

    @Test
    fun `lcsScore partial match`() {
        // LCS of ["a","b","c"] and ["a","x","c"] is ["a","c"], length 2
        // score = 2/3 = 0.667
        assertEquals(0.667, HunkMatcher.lcsScore(listOf("a", "b", "c"), listOf("a", "x", "c")), 0.01)
    }

    @Test
    fun `lcsScore different lengths`() {
        // LCS of ["a","b"] and ["a","b","c","d"] is ["a","b"], length 2
        // score = 2 / max(2,4) = 0.5
        assertEquals(0.5, HunkMatcher.lcsScore(listOf("a", "b"), listOf("a", "b", "c", "d")), 0.001)
    }
}
